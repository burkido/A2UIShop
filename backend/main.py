"""An A2UI agent for the A2UI Shop demo, with two modes.

- AI mode (when GEMINI_API_KEY is set, e.g. in `.env`): Gemini writes the UI. See llm_agent.py.
- Script mode (no key, or AGENT_MODE=script): picks a scripted reply from `scripts/`, no LLM.

Both stream NDJSON (one JSON object per line):

  {"text": "..."}                                 -> chat text for the user
  {"version": "v0.9", "createSurface": {...}}     -> an A2UI protocol message
  {"delayMs": 700}                                -> not sent; only makes the stream feel like
                                                     a real model, so progressive rendering is visible

Run:  uvicorn main:app --reload --host 0.0.0.0 --port 8000
"""

import asyncio
import json
import os
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

SCRIPTS_DIR = Path(__file__).parent / "scripts"


def load_env_file(path: Path):
    """Reads KEY=value lines from `.env`, so the API key stays out of the code."""
    if path.exists():
        for line in path.read_text().splitlines():
            key, sep, value = line.partition("=")
            if sep and not line.lstrip().startswith("#"):
                os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


load_env_file(Path(__file__).parent / ".env")

# Imported after .env is loaded, because it reads GEMINI_MODEL and GEMINI_THINKING_LEVEL.
from llm_agent import GeminiAgent, user_turn  # noqa: E402

API_KEY = os.environ.get("GEMINI_API_KEY", "")
gemini = GeminiAgent(API_KEY) if API_KEY and os.environ.get("AGENT_MODE") != "script" else None

app = FastAPI(title="A2UI Shop agent")
print(f"Agent mode: {'AI (Gemini)' if gemini else 'scripts'}", flush=True)


class AgentRequest(BaseModel):
    # One of the two is set: what the user typed, or an A2UI client message (action / error).
    prompt: str | None = None
    clientMessage: dict | None = None
    # IDs of the surfaces on the client's screen, so the agent doesn't edit UI that isn't there.
    activeSurfaces: list[str] = []
    # One chat. The AI agent remembers the conversation per session; the app starts a new one on Clear.
    sessionId: str = "default"


def pick_script(request: AgentRequest) -> tuple[str, dict]:
    """Returns the script name and template variables, a stand-in for the LLM's reasoning."""
    if request.prompt is not None:
        prompt = request.prompt.lower()
        has_any = lambda *words: any(w in prompt for w in words)
        if has_any("what size", "which size", "size guide", "my size"):
            return "size_guide_broken", {}
        # These two change UI that is already on screen, so they check that it's there.
        if has_any("under", "cheap", "budget", "less than"):
            return ("filter_results" if "sneaker-results" in request.activeSurfaces else "no_results"), {}
        if "pair" in prompt and "checkout" in request.activeSurfaces:
            return "update_quantity", {}
        if has_any("sneaker", "shoe", "trainer", "runner"):
            return "search_results", {}
        if "pair" in prompt:
            return "no_checkout", {}
        return "fallback", {}

    message = request.clientMessage or {}
    if action := message.get("action"):
        context = action.get("context", {})
        if action.get("name") == "selectProduct":
            # formatCurrency only accepts a double, and a whole price like 69 arrives as an int.
            return "checkout", {**context, "price": float(context.get("price", 0))}
        if action.get("name") == "placeOrder":
            delivery = "Express: arrives tomorrow" if context.get("express") else "Standard: arrives in 3–5 days"
            return "order_placed", {**context, "delivery": delivery}
    if error := message.get("error"):
        # The self-correction loop: the client says the UI was invalid, so the agent fixes it.
        # Only an invalid UI is worth self-correcting, not e.g. an image that failed to load.
        if error.get("surfaceId") == "size-guide" and error.get("code") == "VALIDATION_FAILED":
            return "size_guide_fixed", {}
        return "error_ack", {"message": error.get("message", "unknown error")}
    return "fallback", {}


def display(value) -> str:
    """Turns an action context value into readable text: 2.0 -> "2", ["42"] -> "42"."""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, list):
        return ", ".join(display(v) for v in value)
    return str(value)


def load_script(name: str, variables: dict) -> list[dict]:
    """Fills {{name}} placeholders.

    A placeholder that is a whole JSON string ("{{price}}") keeps the value's type, so numbers
    stay numbers. Inside text, it becomes readable text.
    """
    raw = (SCRIPTS_DIR / f"{name}.json").read_text()
    for key, value in variables.items():
        raw = raw.replace('"{{' + key + '}}"', json.dumps(value))
        raw = raw.replace("{{" + key + "}}", json.dumps(display(value))[1:-1])  # escape for a JSON string
    return json.loads(raw)


@app.post("/agent")
async def agent(request: AgentRequest) -> StreamingResponse:
    print(f"-> {request.model_dump(exclude_none=True)}", flush=True)
    stream = ai_reply(request) if gemini else scripted_reply(request)
    return StreamingResponse(ndjson(stream), media_type="application/x-ndjson")


async def ndjson(steps):
    async for step in steps:
        yield json.dumps(step, ensure_ascii=False) + "\n"


async def scripted_reply(request: AgentRequest):
    script, variables = pick_script(request)
    print(f"<- script: {script}", flush=True)
    for step in load_script(script, variables):
        if "delayMs" in step:
            await asyncio.sleep(step["delayMs"] / 1000)
        else:
            yield step


async def ai_reply(request: AgentRequest):
    turn = user_turn(request.activeSurfaces, prompt=request.prompt, client_message=request.clientMessage)
    sent_anything = False
    try:
        async for step in gemini.reply(request.sessionId, turn):
            sent_anything = True
            yield step
    except Exception as e:  # network, quota, bad key...
        print(f"   ! AI failed: {e!r}", flush=True)
        if sent_anything:
            yield {"text": "\n\n⚠️ The AI model stopped halfway. Please try again."}
            return
        # Keep the demo alive: say what happened, then play the scripted reply.
        yield {"text": f"⚠️ AI unavailable ({str(e)[:120]}). Here is the scripted reply instead.\n\n"}
        async for step in scripted_reply(request):
            yield step
