(() => {
  const id = 'screenshot-answers-panel';
  const existing = document.getElementById(id);
  if (existing) { existing.remove(); return; }
  const host = document.createElement('div'); host.id = id;
  host.style.cssText = 'position:fixed;top:12px;right:12px;width:340px;max-width:90vw;z-index:2147483647';
  const root = host.attachShadow({mode: 'closed'});
  const style = document.createElement('style');
  style.textContent = ':host{all:initial}section{font:12px/1.45 Arial,sans-serif;background:#fff;color:#17202a;border:1px solid #b7c4d1;border-radius:8px;box-shadow:0 3px 15px #0003}header{display:flex;align-items:center;gap:8px;padding:8px;border-bottom:1px solid #ddd}strong{flex:1}button{cursor:pointer}pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;margin:0;padding:10px;max-height:45vh;overflow:auto}small{display:block;padding:0 10px 8px;color:#526170}';
  const box = document.createElement('section');
  const header = document.createElement('header');
  const title = document.createElement('strong'); title.textContent = 'Ответ на скриншот';
  const close = document.createElement('button'); close.textContent = '×'; close.title = 'Закрыть панель';
  close.onclick = () => host.remove(); header.append(title, close);
  const content = document.createElement('pre');
  const status = document.createElement('small');
  box.append(header, content, status); root.append(style, box); document.documentElement.append(host);
  let timer;
  async function update() {
    if (!host.isConnected) return;
    try {
      const result = await chrome.runtime.sendMessage({type: 'answer'});
      content.textContent = result?.text || ''; status.textContent = result?.state || 'Ожидание';
    } catch { status.textContent = 'Расширение обновлено — откройте панель заново'; return; }
    timer = setTimeout(update, 1500);
  }
  update();
})();
