"""A real A2UI agent, powered by Gemini.

The model gets the app's catalog (the JSON Schema the app itself exported), the store inventory,
and worked examples, and writes NDJSON: a chat line, then A2UI messages. We stream its output and
forward each JSON object as soon as it is complete, so the app renders UI while the model is still
writing (progressive rendering for free).

The app validates every message against its catalog. If the model gets something wrong, the app
sends a VALIDATION_FAILED error back, and the model fixes its own UI on the next turn.
"""

import json
import os
import re
import time
from collections.abc import AsyncIterator
from pathlib import Path

import httpx

ROOT = Path(__file__).parent
SCRIPTS_DIR = ROOT / "scripts"
CATALOG_ID = "https://example.com/a2ui/shop/v1/catalog.json"
API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse"

MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.5-flash-lite")
# "low" keeps the first UI on screen within a few seconds. Try "medium" or "high" for harder asks.
THINKING_LEVEL = os.environ.get("GEMINI_THINKING_LEVEL", "low")
MAX_HISTORY_TURNS = 12  # user + model pairs kept per chat


# ---------------------------------------------------------------------------------------------
# The system prompt
# ---------------------------------------------------------------------------------------------

def _catalog_schema() -> str:
    """The schema the app exported with `catalog.toJsonSchemaString()`, minus what we don't support."""
    schema = json.loads((ROOT / "data" / "shop_catalog.json").read_text())
    for name in ("Video", "AudioPlayer"):  # the app plugs in no media renderer
        schema["components"].pop(name, None)
    schema["$defs"]["anyComponent"]["oneOf"] = [
        ref for ref in schema["$defs"]["anyComponent"]["oneOf"]
        if ref["$ref"].rsplit("/", 1)[-1] in schema["components"]
    ]
    text = json.dumps(schema, separators=(",", ":"), ensure_ascii=False)
    # Every component repeats the same long description for `weight`. Say it once instead.
    weight = ('"description":"The relative weight of this component within a Row or Column. This is similar '
              'to the CSS \'flex-grow\' property. Note: this may ONLY be set when the component is a direct '
              'descendant of a Row or Column."')
    text = text.replace(weight, '"description":"flex-grow inside a Row or Column"')
    return text


def _products() -> list[dict]:
    return json.loads((ROOT / "data" / "products.json").read_text())


# The model writes "image:<product id>" instead of a URL. That keeps ~6k tokens of URLs out of the
# prompt, and the model can't mistype a URL. normalize() swaps in the real URL.
IMAGE_URLS = {p["id"]: p["imageUrl"] for p in _products()}


def _to_image_refs(text: str) -> str:
    for product_id, url in IMAGE_URLS.items():
        text = text.replace(url, f"image:{product_id}")
    return text


def _script(name: str) -> list[dict]:
    return [step for step in json.loads((SCRIPTS_DIR / f"{name}.json").read_text()) if "delayMs" not in step]


def _fill(steps: list[dict], **values) -> list[dict]:
    raw = json.dumps(steps, ensure_ascii=False)
    for key, value in values.items():
        raw = raw.replace(f'"{{{{{key}}}}}"', json.dumps(value, ensure_ascii=False))
        raw = raw.replace(f"{{{{{key}}}}}", str(value))
    return json.loads(raw)


def _with_text(steps: list[dict], text: str) -> list[dict]:
    """The scripts' chat lines explain the demo to a developer. A shopper should read something else."""
    return [{"text": text}] + [step for step in steps if "text" not in step]


def user_turn(active_surfaces: list[str], prompt: str | None = None, client_message: dict | None = None) -> str:
    lines = [f"activeSurfaces: {json.dumps(active_surfaces)}"]
    if prompt is not None:
        lines.append(f"user: {prompt}")
    if client_message is not None:
        lines.append(f"clientMessage: {json.dumps(client_message, ensure_ascii=False, separators=(',', ':'))}")
    return "\n".join(lines)


def _ndjson(steps: list[dict]) -> str:
    return "\n".join(json.dumps(step, ensure_ascii=False, separators=(",", ":")) for step in steps)


def _examples() -> str:
    court = next(p for p in _products() if p["id"] == "court-classic")
    product_context = {"productId": court["id"], "name": court["name"], "price": court["price"],
                       "imageUrl": court["imageUrl"]}
    checkout = _fill(_script("checkout"), productId=court["id"], name=court["name"], price=court["price"],
                     imageUrl=court["imageUrl"])
    sizes = [{"label": s, "value": s} for s in court["sizes"]]
    for step in checkout:
        for component in step.get("updateComponents", {}).get("components", []):
            if component["id"] == "size":
                component["options"] = sizes

    order = [
        {"text": "Your order is in! I've emailed the receipt."},
        {"version": "v0.9", "deleteSurface": {"surfaceId": "checkout"}},
        {"version": "v0.9", "createSurface": {"surfaceId": "order-placed", "catalogId": CATALOG_ID}},
        {"version": "v0.9", "updateComponents": {"surfaceId": "order-placed", "components": [
            {"id": "root", "component": "Card", "child": "content"},
            {"id": "content", "component": "Column", "align": "stretch",
             "children": ["header", "orderNumber", "details", "total", "delivery", "receipt"]},
            {"id": "header", "component": "Row", "align": "center", "children": ["checkIcon", "headline"]},
            {"id": "checkIcon", "component": "Icon", "name": "check"},
            {"id": "headline", "component": "Text", "variant": "h4", "text": "Order placed"},
            {"id": "orderNumber", "component": "Text", "variant": "caption", "text": "Order A2-48213"},
            {"id": "details", "component": "Text", "text": "2 × Court Classic · EU 42"},
            {"id": "total", "component": "Text", "variant": "h5",
             "text": {"call": "formatCurrency", "returnType": "string", "args": {"value": 147.0, "currency": "EUR"}}},
            {"id": "delivery", "component": "Text", "text": "Express: arrives tomorrow"},
            {"id": "receipt", "component": "Text", "variant": "caption", "text": "Receipt sent to sam@example.com"},
        ]}},
    ]

    examples = [
        ("A new search",
         user_turn([], prompt="I need new sneakers for everyday wear"),
         _with_text(_script("search_results"), "Here are four I'd look at. Swipe through them and tap one to choose a size.")),
        ("Refining results that are already on screen: data only",
         user_turn(["sneaker-results"], prompt="Anything under €100?"),
         _with_text(_script("filter_results"), "These three are under €100, cheapest first.")),
        ("The user tapped a ProductCard",
         user_turn(["sneaker-results"], client_message={"version": "v0.9", "action": {
             "name": "selectProduct", "surfaceId": "sneaker-results", "sourceComponentId": "product",
             "context": product_context}}),
         _with_text(checkout, "Good pick. Choose a size and tell me where to send the receipt.")),
        ("Changing a value in a form that is on screen: data only",
         user_turn(["sneaker-results", "checkout"], prompt="Actually make it 2 pairs"),
         _with_text(_script("update_quantity"), "Done, 2 pairs.")),
        ("The user placed the order",
         user_turn(["sneaker-results", "checkout"], client_message={"version": "v0.9", "action": {
             "name": "placeOrder", "surfaceId": "checkout", "sourceComponentId": "placeOrder",
             "context": {"productId": "court-classic", "name": "Court Classic", "size": ["42"], "quantity": 2,
                         "express": True, "email": "sam@example.com"}}}),
         order),
    ]
    text = "\n\n".join(
        f"## Example {i}: {title}\n\nUser turn:\n{turn}\n\nYour reply:\n{_ndjson(reply)}"
        for i, (title, turn, reply) in enumerate(examples, start=1)
    )
    # The scripts were written for the sneaker-only demo. In AI mode, the results surface and its button are generic.
    return (_to_image_refs(text).replace("sneaker-results", "product-results")
            .replace("\"actionLabel\":\"Choose size\"", "\"actionLabel\":\"Select\""))


def _for_prompt(product: dict) -> dict:
    """The model copies what it sees, so a price of 249.0 ends up as "€249.0" in its text.
    Show whole prices as people write them. _clean() makes them decimals again for formatCurrency."""
    p = {k: v for k, v in product.items() if k != "imageUrl"}
    if isinstance(p.get("price"), float) and p["price"].is_integer():
        p["price"] = int(p["price"])
    return p


def build_system_prompt() -> str:
    products = "\n".join(
        json.dumps(_for_prompt(p), ensure_ascii=False, separators=(",", ":")) for p in _products()
    )
    return ((ROOT / "prompt.md").read_text()
            .replace("{{CATALOG_ID}}", CATALOG_ID)
            .replace("{{CATALOG_SCHEMA}}", _catalog_schema())
            .replace("{{PRODUCTS}}", products)
            .replace("{{EXAMPLES}}", _examples()))


SYSTEM_PROMPT = build_system_prompt()


# ---------------------------------------------------------------------------------------------
# Cleaning up what the model writes
# ---------------------------------------------------------------------------------------------

A2UI_KEYS = ("createSurface", "updateComponents", "updateDataModel", "deleteSurface")
MONEY_KEY = re.compile(r"price|total|cost|amount", re.IGNORECASE)


def _clean(value, key: str = ""):
    """Fixes what the model can't easily get right.

    - formatCurrency rejects integers, so prices become decimals (69 -> 69.0) wherever they appear.
    - "image:<product id>" becomes the product's real image URL.
    """
    if isinstance(value, str) and value.startswith("image:"):
        return IMAGE_URLS.get(value[6:].strip(), value)
    if isinstance(value, dict):
        if value.get("call") == "formatCurrency" and isinstance(value.get("args"), dict):
            args = value["args"]
            if isinstance(args.get("value"), int) and not isinstance(args.get("value"), bool):
                args["value"] = float(args["value"])
        return {k: _clean(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [_clean(v, key) for v in value]
    if isinstance(value, int) and not isinstance(value, bool) and MONEY_KEY.search(key):
        return float(value)
    return value


def _no_vertical_weight(components: list, column_children: set) -> None:
    """`weight` inside a Column stretches a child to fill the surface's height (900dp in the app),
    which turns a 3-row table into a page of empty space. In a chat, that's never what you want.

    `column_children` remembers a surface's Column children across messages, because the model
    often sends the Column in one updateComponents and its rows in the next.
    """
    column_children.update(child for c in components if isinstance(c, dict) and c.get("component") == "Column"
                           and isinstance(c.get("children"), list) for child in c["children"])
    for c in components:
        if isinstance(c, dict) and c.get("id") in column_children:
            c.pop("weight", None)


def normalize(obj: dict, column_children: dict[str, set] | None = None) -> dict | None:
    """Returns the line to send to the app, or None to drop it."""
    if not isinstance(obj, dict):
        return None
    if any(key in obj for key in A2UI_KEYS):
        obj = {"version": "v0.9", **{k: v for k, v in obj.items() if k != "version"}}
        update = obj.get("updateComponents", {})
        if isinstance(update.get("components"), list):
            known = (column_children if column_children is not None else {}).setdefault(update.get("surfaceId"), set())
            _no_vertical_weight(update["components"], known)
        return _clean(obj)
    if isinstance(obj.get("text"), str):
        return {"text": obj["text"]}
    return None


class NdjsonStream:
    """Turns streamed text into JSON objects as soon as each one is complete.

    The model is asked for one object per line, but this also copes with pretty-printed objects,
    two objects on one line, code fences, and stray prose.
    """

    START = re.compile(r'\n\s*\{\s*"(version|text|createSurface|updateComponents|updateDataModel|deleteSurface)"')

    def __init__(self):
        self.buffer = ""
        self.decoder = json.JSONDecoder()

    def feed(self, chunk: str, final: bool = False) -> list[dict]:
        self.buffer += chunk
        objects = []
        while True:
            self.buffer = self.buffer.lstrip()
            if not self.buffer:
                break
            if self.buffer.startswith("{"):
                try:
                    obj, end = self.decoder.raw_decode(self.buffer)
                    objects.append(obj)
                    self.buffer = self.buffer[end:]
                    continue
                except json.JSONDecodeError:
                    # Incomplete (wait for more) or broken (skip to the next object).
                    next_start = self.START.search(self.buffer)
                    if next_start:
                        print(f"   ! dropped broken JSON: {self.buffer[:next_start.start()][:200]}", flush=True)
                        self.buffer = self.buffer[next_start.start():]
                        continue
                    if final:
                        print(f"   ! dropped broken JSON: {self.buffer[:200]}", flush=True)
                        self.buffer = ""
                    break
            newline = self.buffer.find("\n")
            if newline == -1 and not final:
                break
            line = self.buffer if newline == -1 else self.buffer[:newline]
            self.buffer = "" if newline == -1 else self.buffer[newline + 1:]
            line = line.strip()
            if line and not line.startswith("```"):
                objects.append({"text": line})  # prose outside JSON: still worth showing
        return objects


# ---------------------------------------------------------------------------------------------
# The agent
# ---------------------------------------------------------------------------------------------

class GeminiAgent:
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.histories: dict[str, list[dict]] = {}
        self.column_children: dict[str, dict[str, set]] = {}  # session -> surface -> ids inside a Column
        self.client = httpx.AsyncClient(timeout=httpx.Timeout(90.0, connect=10.0))

    async def reply(self, session_id: str, turn: str) -> AsyncIterator[dict]:
        """Streams the reply as JSON objects. Raises before the first object if the model is unreachable."""
        history = self.histories.setdefault(session_id, [])
        contents = history + [{"role": "user", "parts": [{"text": turn}]}]
        body = {
            "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
            "contents": contents,
            "generationConfig": {"thinkingConfig": {"thinkingLevel": THINKING_LEVEL}, "maxOutputTokens": 16384},
        }
        emitted: list[dict] = []
        layout = self.column_children.setdefault(session_id, {})
        parser = NdjsonStream()
        usage = {}
        started = time.monotonic()

        async with self.client.stream(
            "POST", API_URL.format(model=MODEL), json=body,
            headers={"x-goog-api-key": self.api_key, "Content-Type": "application/json"},
        ) as response:
            if response.status_code != 200:
                detail = (await response.aread()).decode(errors="replace")
                try:
                    detail = json.loads(detail)["error"]["message"]
                except (ValueError, KeyError, TypeError):
                    pass
                raise RuntimeError(f"Gemini {response.status_code}: {detail[:300]}")

            async for sse_line in response.aiter_lines():
                if not sse_line.startswith("data:"):
                    continue
                chunk = json.loads(sse_line[5:])
                usage = chunk.get("usageMetadata", usage)
                parts = (chunk.get("candidates") or [{}])[0].get("content", {}).get("parts", [])
                text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
                for obj in parser.feed(text):
                    if line := self._accept(obj, emitted, started, layout):
                        yield line
            for obj in parser.feed("", final=True):
                if line := self._accept(obj, emitted, started, layout):
                    yield line

        if not emitted:
            yield {"text": "Sorry, I lost my train of thought. Could you say that again?"}
            return
        history += [
            {"role": "user", "parts": [{"text": turn}]},
            {"role": "model", "parts": [{"text": _ndjson(emitted)}]},
        ]
        del history[:-MAX_HISTORY_TURNS * 2]
        cached = usage.get("cachedContentTokenCount", 0)
        print(f"   {MODEL}: {time.monotonic() - started:.1f}s · in {usage.get('promptTokenCount')} "
              f"(cached {cached}) · out {usage.get('candidatesTokenCount')} · thinking {usage.get('thoughtsTokenCount', 0)}",
              flush=True)

    @staticmethod
    def _accept(obj: dict, emitted: list[dict], started: float, layout: dict[str, set]) -> dict | None:
        line = normalize(obj, layout)
        if line is None:
            print(f"   ! dropped: {str(obj)[:200]}", flush=True)
            return None
        if "text" in line and any("text" in e for e in emitted):
            line = {"text": "\n\n" + line["text"]}  # the app joins text lines as they are
        emitted.append(line)
        kind = next((k for k in A2UI_KEYS if k in line), "text")
        surface = line.get(kind, {}).get("surfaceId", "") if kind != "text" else line["text"][:80]
        print(f"   {time.monotonic() - started:5.1f}s {kind} {surface}", flush=True)
        return line
