import { randomUUID } from 'node:crypto';
import { access, mkdir, readFile, readdir, stat, unlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { gunzip } from 'node:zlib';

const MAX_JSON_BYTES = 128 * 1024 * 1024;
// Gzip can slightly increase the size of incompressible input.
const MAX_COMPRESSED_BYTES = MAX_JSON_BYTES + 1024 * 1024;
const MAX_PROFILES = 32;
const PROFILE_ID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const PROFILE_FILE = new RegExp(`^${PROFILE_ID}\\.json$`);
const PROFILE_PATH = new RegExp(`^/v1/get/(${PROFILE_ID})$`);
const VIEWER_PATH = new RegExp(`^/p(?:/${PROFILE_ID})?(?:/(?:heatmap|chunks|aggregated|traces|calls|info))?/?$`);
const CONTENT_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.woff': 'font/woff',
    '.woff2': 'font/woff2',
};
const unzip = promisify(gunzip);

export async function startProfileReceiver({
    port = 0,
    directory,
    publicUrl,
    viewerDirectory = fileURLToPath(new URL('./public', import.meta.url)),
}) {
    const publicOrigin = publicUrl ? parsePublicOrigin(publicUrl) : null;
    const profiles = resolve(directory);
    const viewer = resolve(viewerDirectory);
    await access(resolve(viewer, 'index.html'));
    await mkdir(profiles, { recursive: true });
    await pruneProfiles();
    let pendingWrites = Promise.resolve();

    async function pruneProfiles(newestId) {
        const entries = await readdir(profiles, { withFileTypes: true });
        const saved = await Promise.all(entries
            .filter(entry => entry.isFile() && PROFILE_FILE.test(entry.name))
            .map(async entry => ({ name: entry.name, modified: (await stat(resolve(profiles, entry.name))).mtimeMs })));
        saved.sort((a, b) => Number(b.name === `${newestId}.json`) - Number(a.name === `${newestId}.json`)
            || b.modified - a.modified || b.name.localeCompare(a.name));
        await Promise.all(saved.slice(MAX_PROFILES).map(entry => unlink(resolve(profiles, entry.name))));
    }

    function saveProfile(id, json) {
        const saved = pendingWrites.then(async () => {
            await writeFile(resolve(profiles, `${id}.json`), json, { flag: 'wx' });
            await pruneProfiles(id);
        });
        // Serialize writes and retention so concurrent uploads cannot delete each other's new reports.
        pendingWrites = saved.catch(() => {});
        return saved;
    }

    const server = createServer({ headersTimeout: 10_000, requestTimeout: 45_000 }, async (request, response) => {
        const path = request.url?.split('?', 1)[0];
        try {
            if (request.method === 'GET' && path === '/health') {
                return reply(response, 200, 'ok');
            }
            if (request.method === 'POST' && path === '/v1/add') {
                if (request.headers['content-encoding'] !== 'gzip') {
                    return reply(response, 415, 'Expected a gzip-compressed JSON profile.');
                }

                const chunks = [];
                let length = 0;
                for await (const chunk of request.iterator({ destroyOnReturn: false })) {
                    length += chunk.length;
                    if (length > MAX_COMPRESSED_BYTES) {
                        response.setHeader('Connection', 'close');
                        request.resume();
                        return reply(response, 413, 'Compressed profile is too large.');
                    }
                    chunks.push(chunk);
                }

                let json;
                try {
                    json = await unzip(Buffer.concat(chunks, length), { maxOutputLength: MAX_JSON_BYTES });
                    const parsed = JSON.parse(json.toString('utf8'));
                    if (!Number.isInteger(parsed?.data?.ticks) || parsed.data.ticks < 0 ||
                        !Number.isInteger(parsed.data.timelineTicks) || parsed.data.timelineTicks < 0 ||
                        typeof parsed.diagnostics?.owner?.name !== 'string') {
                        return reply(response, 400, 'Expected a TickSight profile and diagnostics.');
                    }
                } catch (error) {
                    const status = error.code === 'ERR_BUFFER_TOO_LARGE' ? 413 : 400;
                    return reply(response, status, 'Could not read gzip JSON profile.');
                }

                const id = randomUUID();
                await saveProfile(id, json);
                const origin = publicOrigin ?? `http://127.0.0.1:${server.address().port}`;
                return reply(response, 201, `${origin}/p/${id}`);
            }

            const match = request.method === 'GET' && PROFILE_PATH.exec(path);
            if (match) {
                const file = await readFile(resolve(profiles, `${match[1]}.json`));
                return reply(response, 200, file, 'application/json; charset=utf-8');
            }
            if (request.method === 'GET' && (path === '/' || VIEWER_PATH.test(path))) {
                return reply(response, 200, await readFile(resolve(viewer, 'index.html')), CONTENT_TYPES['.html']);
            }
            if (request.method === 'GET' && (path?.startsWith('/_app/') || path === '/favicon.png')) {
                const asset = resolve(viewer, '.' + decodeURIComponent(path));
                const localPath = relative(viewer, asset);
                if (localPath.startsWith('..') || isAbsolute(localPath)) {
                    return reply(response, 404, 'Asset not found.');
                }
                const file = await readFile(asset);
                return reply(response, 200, file, CONTENT_TYPES[extname(asset)] ?? 'application/octet-stream');
            }
            return reply(response, 404, 'Profile or endpoint not found.');
        } catch (error) {
            if (error.code === 'ENOENT') return reply(response, 404, 'Profile not found.');
            console.error('Could not process the profile:', error.code ?? error.message);
            if (!response.headersSent) reply(response, 500, 'Could not process the profile.');
        }
    });

    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, '127.0.0.1', resolve);
    });
    return server;
}

function parsePublicOrigin(value) {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol)
        || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
        throw new Error('Public URL must be an HTTP(S) origin such as https://ticksight.anixty.net');
    }
    return url.origin;
}

function reply(response, status, body, contentType = 'text/plain; charset=utf-8') {
    response.writeHead(status, {
        'Content-Type': contentType,
        'X-Content-Type-Options': 'nosniff',
        'Cache-Control': 'no-store',
    });
    response.end(body);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [port = '8765', directory = 'profiles', publicUrl] = process.argv.slice(2);
    const server = await startProfileReceiver({ port: Number(port), directory, publicUrl });
    const origin = publicUrl ? parsePublicOrigin(publicUrl) : `http://127.0.0.1:${server.address().port}`;
    console.log(`Listening on http://127.0.0.1:${server.address().port}`);
    console.log(`Set uploadURL to ${origin}/v1/add`);
    console.log(`Profiles are saved to ${resolve(directory)}`);
}
