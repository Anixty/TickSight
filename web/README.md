# TickSight web

The profile receiver and browser viewer live together in this directory. The viewer is
based on [tasgon's Observable web interface](https://github.com/tasgon/observable_web)
and includes Individual, Chunks, Aggregate, Info, and Heatmap. Calls or Traces appear
according to each report's data.

## Open a profile

Open the viewer home page to select a local JSON profile. A link returned after an upload opens that report in the viewer.

## Build from source

Use Node.js 20 or newer. From the repository root, run:

```sh
cd web
npm ci
npm run build
```

The build writes the static viewer to `public/`.

## Self-host

Copy `web/profile-receiver.mjs` and the generated `web/public/` directory together to
the deployment directory. The receiver needs Node.js 20 or newer and uses only
built-in modules, so npm packages are not required at runtime. From that directory,
start it with a profile storage directory and your public HTTP(S) origin:

```sh
node ./profile-receiver.mjs 8765 ./profiles https://profiles.example.com
```

Replace `https://profiles.example.com` with your own public origin, without a path.
The receiver listens on `127.0.0.1:8765`; configure an HTTPS reverse proxy for that
origin to forward requests to `http://127.0.0.1:8765` without rewriting request paths.

Set TickSight's `uploadURL` to the matching upload endpoint:

```text
/ticksight set uploadURL "https://profiles.example.com/v1/add"
```

The receiver saves uploaded reports under `./profiles`, keeps the latest 32, and accepts
up to 128 MiB of uncompressed JSON. `POST /v1/add` accepts gzip-compressed profile JSON
with diagnostics and returns a viewer URL such as
`https://profiles.example.com/p/<UUID>` on the configured origin.
`GET /v1/get/<UUID>` serves the profile JSON, and `GET /health` returns `ok`.

## Source attribution

The viewer's upstream revision, local adaptations, and license status are recorded in [UPSTREAM.md](UPSTREAM.md).
