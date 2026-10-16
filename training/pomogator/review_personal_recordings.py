"""Local-only audio review for train/validation candidates. Never serves test or originals."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


HTML = r'''<!doctype html><html lang="ru"><meta charset="utf-8">
<title>Проверка «Эй, Помогатор»</title>
<style>
body{font:19px system-ui;background:#122033;color:#eef4ff;max-width:850px;margin:40px auto;padding:20px}
h1{font-size:28px}p{line-height:1.55}button{font:inherit;padding:14px 20px;margin:6px;border:0;border-radius:10px;cursor:pointer}
.yes{background:#8ce6b3}.no{background:#ffb0a9}.nav{background:#d6e5f9}audio{width:100%;margin:20px 0}
#card{background:#1e324d;padding:24px;border-radius:16px}#warn{color:#ffd390}#saved{color:#8ce6b3}small{color:#b6c9e5}
</style>
<h1>Полная фраза «Эй, Помогатор»?</h1>
<p>Это локальная проверка нарезки. Файлы никуда не загружаются. На каждом фрагменте
должно быть ровно одно полное <b>«Эй, Помогатор»</b>, без обрезанных начала и конца.
Если слышно только «эй», только «Помогатор», несколько повторов или не уверен — отбрось.</p>
<div id="card"><div id="counter"></div><h2 id="name"></h2><div id="warn"></div>
<audio id="audio" controls preload="none"></audio><div id="choice"></div>
<button class="yes" id="yes">Полная фраза → дальше</button>
<button class="no" id="no">Отбросить → дальше</button><br>
<button class="nav" id="prev">← Назад</button><button class="nav" id="next">Пропустить →</button>
<div id="saved"></div></div>
<p><small>Сначала train, затем validation. Test здесь не показывается.
Результаты сохраняются автоматически только на этом компьютере.</small></p>
<script>
let state, index=0, busy=false;
const el=id=>document.getElementById(id);
function render(play=false){
 const item=state.clips[index];
 const done=state.clips.filter(c=>state.decisions[c.id]!==undefined).length;
 el('counter').textContent=`${index+1} / ${state.clips.length} · проверено ${done}`;
 el('name').textContent=item.id;
 const flags={short_fragment:'короткий фрагмент — проверь начало и конец',possibly_multiple_phrases:'возможно, несколько повторов'};
 el('warn').textContent=item.flags.length?'Автоматическая пометка: '+item.flags.map(f=>flags[f]||f).join(', '):'';
 el('audio').src='/audio/'+encodeURIComponent(item.id);
 const decision=state.decisions[item.id];
 el('choice').textContent=decision===undefined?'Ещё не проверено':decision?'Решение: полная фраза':'Решение: отбросить';
 if(play)el('audio').play().catch(()=>{});
}
async function decide(accepted){
 if(busy)return;busy=true;
 const id=state.clips[index].id;
 try{
  const response=await fetch('/decision',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,accepted})});
  if(!response.ok)throw Error('Сохранение не удалось');
  state.decisions[id]=accepted;
  const done=state.clips.filter(c=>state.decisions[c.id]!==undefined).length;
  el('saved').textContent=done===state.clips.length?'Все решения сохранены. Напиши в чат «проверил».':'Сохранено';
  if(index<state.clips.length-1){index++;render(true)}else{el('audio').pause();render()}
 }catch(error){el('saved').textContent=error.message}finally{busy=false}
}
el('yes').onclick=()=>decide(true);el('no').onclick=()=>decide(false);
el('prev').onclick=()=>{if(index>0){index--;render(true)}};
el('next').onclick=()=>{if(index<state.clips.length-1){index++;render(true)}};
fetch('/state').then(r=>r.json()).then(data=>{state=data;const pending=state.clips.findIndex(c=>state.decisions[c.id]===undefined);index=pending<0?0:pending;render()});
</script></html>'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--train', type=Path, required=True)
    parser.add_argument('--validation', type=Path, required=True)
    parser.add_argument('--decisions', type=Path, required=True)
    parser.add_argument('--port', type=int, default=8766)
    args = parser.parse_args()
    clips, paths = [], {}
    provenance = {}
    for split, directory in [('train', args.train), ('validation', args.validation)]:
        manifest = json.loads((directory / 'review.json').read_text(encoding='utf-8'))
        if manifest['split'] != split:
            raise ValueError('Wrong split directory')
        provenance[split] = manifest['source_pcm_sha256']
        for item in manifest['clips']:
            identifier = split + '/' + item['file']
            paths[identifier] = directory / item['file']
            clips.append({'id': identifier, 'flags': item['review_flags']})
    if provenance['train'] == provenance['validation']:
        raise ValueError('Train and validation sources must be independent')
    decisions = {}
    if args.decisions.exists():
        saved = json.loads(args.decisions.read_text(encoding='utf-8'))
        if saved['source_pcm_sha256'] != provenance:
            raise ValueError('Saved decisions belong to another dataset')
        decisions.update(saved['decisions'])

    class Handler(BaseHTTPRequestHandler):
        def respond(self, payload, content_type='application/json'):
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            from urllib.parse import unquote
            if self.path == '/':
                self.respond(HTML.encode('utf-8'), 'text/html; charset=utf-8')
            elif self.path == '/state':
                self.respond(json.dumps({'clips': clips, 'decisions': decisions}).encode())
            elif self.path.startswith('/audio/') and unquote(self.path[7:]) in paths:
                self.respond(paths[unquote(self.path[7:])].read_bytes(), 'audio/wav')
            else:
                self.send_error(404)

        def do_POST(self):
            origin = self.headers.get('Origin')
            if self.path != '/decision' or origin not in (None, f'http://127.0.0.1:{args.port}'):
                self.send_error(403)
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if length < 1 or length > 4096:
                    raise ValueError('Invalid request size')
                item = json.loads(self.rfile.read(length))
                if item['id'] not in paths or type(item['accepted']) is not bool:
                    raise ValueError('Invalid decision')
                decisions[item['id']] = item['accepted']
                args.decisions.parent.mkdir(parents=True, exist_ok=True)
                args.decisions.write_text(json.dumps({
                    'source_pcm_sha256': provenance, 'decisions': decisions,
                }, indent=2), encoding='utf-8')
                self.respond(b'{"saved":true}')
            except (ValueError, KeyError):
                self.send_error(400)

        def log_message(self, fmt, *values):
            pass

    print(f'Local review: http://127.0.0.1:{args.port} · {len(clips)} clips', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
