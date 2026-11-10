# A2UI Shop agent

A small FastAPI server that acts as the AI agent for the **A2UI Shop** Android demo,
a shopping assistant that finds sneakers and runs a small checkout. It has two modes:

- **AI mode** (when `GEMINI_API_KEY` is set): Gemini writes the UI as A2UI messages, for a lifestyle store with
  192 products. Ask anything: "something white for a wedding", "I'm going camping next weekend",
  "a gift for my dad who loves coffee", "compare these two", "my foot is 27 cm, what size?".
- **Script mode** (no key, or `AGENT_MODE=script`): plays the scripted replies in `scripts/`, with
  pauses between steps, so the article's demo runs the same every time.

> This backend was placed in the A2UI Shop repository for faster development, so the app and its agent
> change together. See the [main README](../README.md) for the app and a walkthrough of the demo.

```bash
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env   # then paste your Google AI Studio key into .env
.venv/bin/uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

The server prints which mode it runs in. In AI mode it logs every streamed message with a timestamp,
and the token usage for each reply.

Then forward the port to the emulator or a USB-connected phone:

```bash
adb reverse tcp:8000 tcp:8000
```

The app calls `http://localhost:8000`. It doesn't use `10.0.2.2` because apps targeting
Android 17 (SDK 37) can't reach the local network without the `ACCESS_LOCAL_NETWORK` runtime
permission. Loopback through `adb reverse` doesn't need it.

## API

`POST /agent` with either

```json
{ "prompt": "I need new sneakers for everyday wear", "activeSurfaces": [], "sessionId": "..." }
{ "clientMessage": { "version": "v0.9", "action": { "name": "placeOrder", "context": { ... } } }, "activeSurfaces": ["checkout"], "sessionId": "..." }
```

`activeSurfaces` lists the surface IDs on the client's screen, so the agent doesn't edit UI that isn't there.
`sessionId` identifies one chat: the AI agent remembers the conversation per session (in memory, last 12 turns).
The app starts a new session when you tap Clear.

The response is `application/x-ndjson`. Each line is either `{"text": "..."}` (chat text) or an
A2UI server-to-client message (`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`).

## AI mode

`llm_agent.py` sends Gemini a system prompt (`prompt.md`) that contains:

- **The app's catalog**: `data/shop_catalog.json` is the JSON Schema the app itself exported with
  `catalog.toJsonSchemaString()`. It's the exact contract the app validates against, including the custom
  `ProductCard` and `QuantityStepper`. If you change the catalog in the app, export it again.
- **Renderer rules** found while building the demo (`formatCurrency` needs a double, horizontal lists need
  `align: start`, and so on).
- **Surface conventions** (`product-results`, `checkout`, `order-placed`) and the rule to refine with data only.
- **The inventory** in `data/products.json`: 192 products in 17 categories (shoes, clothing, bags, accessories,
  jewelry, tech, sports, outdoor, home, kitchen, beauty, kids, food, books, music, pets, gifts), all with
  fictional brands. Every product has its own Unsplash photo, checked by eye to match the product. `sizes`
  holds EU shoe sizes, clothing sizes, waist sizes and so on. A product without `sizes` is one size, and its
  checkout has no size picker. The first 21 products are the original sneakers, so the scripts still match.
- **Worked examples**, built from the scripts in `scripts/`.

The model streams NDJSON. The server forwards each JSON object as soon as it is complete, so the app renders
the UI while the model is still writing. It also fixes up the model's output a little: it adds a missing
`"version"`, turns whole-number prices into decimals for `formatCurrency`, removes `weight` from anything directly
inside a `Column` (there it stretches rows to fill the 900dp surface), and skips broken lines.

The prompt has no image URLs. The model writes `"image:<product id>"`, and the server swaps in the real URL. That
keeps about 6k tokens out of every request, and the model can't break a URL.

When the app rejects a UI, it sends a `VALIDATION_FAILED` error back, and the model fixes its own UI on
the next turn (the app stops after two automatic retries). If Gemini can't be reached, the server says so and
plays the scripted reply instead.

Settings in `.env`: `GEMINI_MODEL` (default `gemini-3.5-flash-lite`, the cheapest tier) and `GEMINI_THINKING_LEVEL`
(default `low`, which shows the first UI in about 2 seconds). In a test of four turns (search, refine, select,
compare), Flash-Lite produced UI the catalog accepts on every turn, the same as `gemini-3.8-flash`. `minimal` is
faster but wrote a broken JSON line on a bigger UI, so keep `low`. For harder requests, switch to `gemini-3.8-flash`. The system prompt is about
25k tokens. Gemini caches it automatically after the first request, so follow-up turns cost much less.

## Scripts (script mode)

The agent matches keywords in the prompt, so natural phrasings work ("any cheaper ones?").

| Trigger | Script | Shows |
|---|---|---|
| "sneaker", "shoe", "trainer", "runner" | `search_results.json` | progressive rendering, a `List` template over `/products`, custom `ProductCard` |
| "under", "cheap", "budget", "less than" | `filter_results.json` | new data only; the template rebuilds the cards |
| same, but no `sneaker-results` surface | `no_results.json` | the agent knows there is nothing to filter |
| action `selectProduct` | `checkout.json` | a form with two-way binding, `formatString`, `formatCurrency` and `checks` |
| "pair" with a `checkout` surface | `update_quantity.json` | the agent changes one value with `updateDataModel` |
| "pair" without a checkout | `no_checkout.json` | the agent knows there is nothing to change |
| action `placeOrder` | `order_placed.json` | `deleteSurface` for the form, then a confirmation surface |
| "what size", "which size", "size guide" | `size_guide_broken.json` | an unknown component, which the client rejects |
| `VALIDATION_FAILED` error on `size-guide` | `size_guide_fixed.json` | the agent self-corrects in place |

Placeholders like `{{name}}` are filled from the action context. A placeholder that is a whole
JSON string (`"{{price}}"`) keeps the value's type, so numbers stay numbers.

The app ships the same scripts in `app/src/main/assets/scripts/`, so it also runs without this server.
