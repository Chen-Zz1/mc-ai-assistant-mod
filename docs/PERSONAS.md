# Persona cards

Copy one or both fictional examples into `config/mc_ai_assistant/personas/`, then run `/aiadmin reload`:

- [mayor.json](examples/mayor.json): V1, a style description plus short example utterances.
- [companion.json](examples/companion.json): V2, fictional multi-message conversations.

Use `/ai persona list`, `/ai private persona companion hello`, or `/ai settings role companion`. Cards are local server configuration, not a player upload endpoint. V2 is example-based prompting at request time; it does not train or fine-tune the model. Samples are supplied as separate dialogues, not the current conversation's memory. There is no guarantee that additional examples improve perceived similarity.

For V1 use `schemaVersion: 1` (or omit it), `style` and an `examples` list of strings. V2 uses `schemaVersion: 2` and an `examples` list of objects with `id` and `dialogue`; each dialogue message has `speaker` (`other` or `target`) and `text`. V2 does not accept a V1 `style` field. Follow the supplied JSON examples rather than inventing extra fields.

At most 16 personas can be loaded. V2 limits include a 64 KiB file, 16 dialogues, 32 messages per dialogue, 256 messages overall, 2,000 code points per message and 16,000 text code points overall. V1 files are limited to 16 KiB. Invalid/deleted cards do not retain an older active version after reload. Changing a card invalidates its previous context and pending delivery.

Public examples use `kind: fictional`. The optional `kind: real` format requires `consentConfirmed: true`, but the mod cannot verify authorization. No real-person pack, extraction tool or original conversation dataset is distributed. Do not submit such data in public issues or pull requests. All role replies keep an AI label.
