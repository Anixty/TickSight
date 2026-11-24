import { randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gunzipSync } from 'node:zlib';

const MAX_COMPRESSED_BYTES = 16 * 1024 * 1024;
const MAX_JSON_BYTES = 64 * 1024 * 1024;
const PROFILE_PATH = /^\/profile\/([0-9a-f-]{36})$/;

export async function startProfileReceiver({ port = 0, directory }) {
    const profiles = resolve(directory);
    await mkdir(profiles, { recursive: true });

    const server = createServer(async (request, response) => {
        const path = request.url?.split('?', 1)[0];
        try {
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
                    json = gunzipSync(Buffer.concat(chunks, length), { maxOutputLength: MAX_JSON_BYTES });
                    const parsed = JSON.parse(json.toString('utf8'));
                    if (!Number.isInteger(parsed?.data?.ticks) || parsed.data.ticks < 0 || !parsed.diagnostics) {
                        return reply(response, 400, 'Expected a TickSight profile and diagnostics.');
                    }
                } catch (error) {
                    const status = error.code === 'ERR_BUFFER_TOO_LARGE' ? 413 : 400;
                    return reply(response, status, 'Could not read gzip JSON profile.');
                }

                const id = randomUUID();
                await writeFile(resolve(profiles, `${id}.json`), json, { flag: 'wx' });
                return reply(response, 201, `http://127.0.0.1:${server.address().port}/profile/${id}`);
            }

            const match = request.method === 'GET' && PROFILE_PATH.exec(path);
            if (match) {
                const file = await readFile(resolve(profiles, `${match[1]}.json`));
                return reply(response, 200, file, 'application/json; charset=utf-8');
            }
            return reply(response, 404, 'Profile or endpoint not found.');
        } catch (error) {
            if (error.code === 'ENOENT') return reply(response, 404, 'Profile not found.');
            if (!response.headersSent) reply(response, 500, 'Could not process the profile.');
        }
    });

    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, '127.0.0.1', resolve);
    });
    return server;
}

function reply(response, status, body, contentType = 'text/plain; charset=utf-8') {
    response.writeHead(status, { 'Content-Type': contentType, 'X-Content-Type-Options': 'nosniff' });
    response.end(body);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [port = '8765', directory = 'upload-test/build/profiles'] = process.argv.slice(2);
    const server = await startProfileReceiver({ port: Number(port), directory });
    console.log(`Set uploadURL to http://127.0.0.1:${server.address().port}/v1/add`);
    console.log(`Profiles are saved to ${resolve(directory)}`);
}
