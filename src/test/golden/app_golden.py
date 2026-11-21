#!/usr/bin/env python3
"""Golden REST capture for a single Spring Boot app (see run.sh). Starts the app from a classpath
(main build: 'classes:deps') or a fat jar, with HOME and the given args, runs the request list from
--requests (a python file defining requests(work) -> [(METHOD, path, body), ...] and ARGS(work) ->
[app args]), writes one JSON record per request.
METHOD: GET/POST/PUT/DELETE (JSON body), POSTTEXT/PUTTEXT, NDJSON (POST, read the whole stream),
GETNDJSON, SLEEP (path = seconds), FILE (path = file to record)."""
import argparse, re, json, os, runpy, shutil, socket, subprocess, sys, tempfile, time, urllib.request, urllib.error

def repl(text, k, v):
    """Masks a run value: the work dir anywhere, a port only as ':<port>' (not inside other numbers)."""
    if k.isdigit():
        return re.sub(r'(?<=:)' + k + r'(?!\d)', v, text)
    return text.replace(k, v)

def free_port():
    s = socket.socket(); s.bind(('127.0.0.1', 0)); p = s.getsockname()[1]; s.close(); return p

def http(method, url, body=None, ctype='application/json', timeout=60, accept=None):
    data = None
    if body is not None:
        data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None: req.add_header('Content-Type', ctype)
    if accept: req.add_header('Accept', accept)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.headers.get('Content-Type', ''), r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get('Content-Type', ''), e.read().decode('utf-8', 'replace')
    except Exception as e:
        return -1, '', 'EXC ' + type(e).__name__ + ' ' + str(e)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cp', required=True); ap.add_argument('--main-class', required=True)
    ap.add_argument('--requests', required=True); ap.add_argument('--out', required=True)
    ap.add_argument('--work'); ap.add_argument('--health', default='/actuator/health')
    ap.add_argument('--env', action='append', default=[])
    a = ap.parse_args()
    work = a.work or tempfile.mkdtemp(prefix='appgold-')
    shutil.rmtree(work, ignore_errors=True); os.makedirs(work)
    work = os.path.realpath(work)
    spec = runpy.run_path(a.requests)
    port = free_port()
    jvm = ['java', '-Xmx1g', '-Duser.home=' + os.path.join(work, 'home')]
    cmd = jvm + (['-jar', a.cp] if (a.cp.endswith('.jar') and os.pathsep not in a.cp) else ['-cp', a.cp, a.main_class])
    cmd += [f'--server.port={port}'] + spec['ARGS'](work)
    env = dict(os.environ)
    for e in a.env: k, v = e.split('=', 1); env[k] = v.replace('<WORK>', work)
    if 'setup' in spec: spec['setup'](work)
    p = subprocess.Popen(cmd, stdout=open(os.path.join(work, 'app.log'), 'w'), stderr=subprocess.STDOUT, cwd=work, env=env)
    base = f'http://127.0.0.1:{port}'
    try:
        t = time.time()
        while time.time() - t < 180:
            st, _, b = http('GET', base + a.health, timeout=3)
            if st == 200 and 'UP' in b: break
            if p.poll() is not None: break
            time.sleep(1)
        else:
            pass
        if not (st == 200 and 'UP' in b):
            print('app did not come up; log', os.path.join(work, 'app.log'), file=sys.stderr); sys.exit(2)
        subst = {work: '<WORK>', str(port): '<PORT>'}
        records = []
        for i, (m, path, body) in enumerate(spec['requests'](work)):
            if m == 'SLEEP': time.sleep(float(path)); continue
            if m == 'FILE':
                st, ct, b = (0, 'file', open(path, encoding='utf-8', errors='replace').read()) if os.path.exists(path) else (0, 'file', '<missing>')
            elif m in ('POSTTEXT', 'PUTTEXT'):
                st, ct, b = http(m[:-4], base + path, body, ctype='text/plain')
            elif m == 'NDJSON':
                st, ct, b = http('POST', base + path, body, accept='application/x-ndjson')
            elif m == 'GETNDJSON':
                st, ct, b = http('GET', base + path, accept='application/x-ndjson')
            else:
                st, ct, b = http(m, base + path, body)
            rb = body if isinstance(body, (str, type(None))) else json.dumps(body, ensure_ascii=False)
            rq = f'{m} {path}'
            for k, v in subst.items():
                b = repl(b, k, v); rq = repl(rq, k, v)
                if rb is not None: rb = repl(rb, k, v)
            records.append({"i": i, "req": rq, "body": rb, "status": st, "ctype": ct, "resp": b})
        with open(a.out, 'w') as fh:
            for r in records: fh.write(json.dumps(r, ensure_ascii=False) + '\n')
        print('wrote', len(records), 'records to', a.out)
    finally:
        p.terminate()
        try: p.wait(20)
        except Exception: p.kill()

if __name__ == '__main__': main()
