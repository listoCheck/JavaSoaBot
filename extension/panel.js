(() => {
  const id = 'screenshot-answers-panel';
  const existing = document.getElementById(id);
  if (existing) { existing.remove(); return; }
  const host = document.createElement('div'); host.id = id;
  host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;display:block';
  const root = host.attachShadow({mode: 'closed'});
  const style = document.createElement('style');
  style.textContent = `
    *{box-sizing:border-box}section{display:flex;flex-direction:column;width:100%;height:100%;font:12px/1.45 Arial,sans-serif;background:#fff;color:#17202a;border:1px solid #b7c4d1;border-radius:8px;box-shadow:0 3px 15px #0003;resize:both;overflow:hidden;min-width:240px;min-height:180px;max-width:100vw;max-height:100vh}
    header{display:flex;align-items:center;gap:8px;padding:8px;border-bottom:1px solid #ddd;cursor:move;touch-action:none;user-select:none}strong{flex:1}button{cursor:pointer;font:14px Arial;color:#17202a;background:#f3f6fa;border:1px solid #b7c4d1;border-radius:4px;min-width:24px;padding:2px 5px}
    .controls{display:flex;flex-wrap:wrap;align-items:center;gap:6px;padding:6px 8px;border-bottom:1px solid #ddd;font:11px Arial}label{display:flex;align-items:center;gap:4px}input{width:68px}pre{flex:1;min-height:0;font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;margin:0;padding:10px;overflow:auto}small{display:block;padding:0 10px 12px;color:#526170;font:11px Arial}
  `;
  const box = document.createElement('section');
  const header = document.createElement('header');
  const title = document.createElement('strong'); title.textContent = 'Ответ на скриншот';
  const close = document.createElement('button'); close.textContent = '×'; close.title = 'Закрыть панель';
  let stopped = false, timer, saveTimer, observer;
  function cleanup() { stopped = true; clearTimeout(timer); clearTimeout(saveTimer); observer?.disconnect(); window.removeEventListener('resize', onViewport); }
  close.onclick = () => { cleanup(); host.remove(); }; header.append(title, close);
  const controls = document.createElement('div'); controls.className = 'controls';
  function slider(caption, min, max, step) {
    const label = document.createElement('label'); label.append(caption);
    const input = document.createElement('input'); input.type = 'range'; input.min = min; input.max = max; input.step = step;
    input.setAttribute('aria-label', caption); const value = document.createElement('span');
    label.append(input, value); controls.append(label); return {input, value};
  }
  const scale = slider('Масштаб', 75, 200, 5);
  const opacity = slider('Непрозрачность', 25, 100, 5);
  const reset = document.createElement('button'); reset.textContent = 'Сброс'; reset.title = 'Вернуть размер, положение и оформление'; controls.append(reset);
  const content = document.createElement('pre');
  const status = document.createElement('small');
  box.append(header, controls, content, status); root.append(style, box); document.documentElement.append(host);
  const defaults = () => ({x: Math.max(0, innerWidth - 352), y: 12, width: 340, height: Math.min(360, innerHeight), scale: 100, opacity: 100});
  let settings = defaults(), ready = false;
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  function apply() {
    settings.width = clamp(settings.width, Math.min(240, innerWidth), innerWidth);
    settings.height = clamp(settings.height, Math.min(180, innerHeight), innerHeight);
    settings.x = clamp(settings.x, 0, Math.max(0, innerWidth - settings.width));
    settings.y = clamp(settings.y, 0, Math.max(0, innerHeight - settings.height));
    host.style.left = settings.x + 'px'; host.style.top = settings.y + 'px';
    host.style.width = settings.width + 'px'; host.style.height = settings.height + 'px';
    box.style.width = '100%'; box.style.height = '100%';
    box.style.minWidth = Math.min(240, innerWidth) + 'px'; box.style.minHeight = Math.min(180, innerHeight) + 'px';
    box.style.fontSize = (12 * settings.scale / 100) + 'px'; box.style.opacity = settings.opacity / 100;
    scale.input.value = settings.scale; scale.value.textContent = settings.scale + '%';
    opacity.input.value = settings.opacity; opacity.value.textContent = settings.opacity + '%';
  }
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => { chrome.storage.local.set({panelSettings: {...settings}}).catch(() => {}); }, 200);
  }
  scale.input.oninput = () => { settings.scale = Number(scale.input.value); apply(); save(); };
  opacity.input.oninput = () => { settings.opacity = Number(opacity.input.value); apply(); save(); };
  reset.onclick = () => { settings = defaults(); apply(); save(); };
  header.addEventListener('pointerdown', event => {
    if (event.button !== 0 || event.target.closest('button')) return;
    event.preventDefault(); header.setPointerCapture(event.pointerId);
    const startX = event.clientX, startY = event.clientY, x = settings.x, y = settings.y;
    const move = e => { settings.x = x + e.clientX - startX; settings.y = y + e.clientY - startY; apply(); };
    const end = () => { header.removeEventListener('pointermove', move); header.removeEventListener('pointerup', end); header.removeEventListener('pointercancel', end); save(); };
    header.addEventListener('pointermove', move); header.addEventListener('pointerup', end); header.addEventListener('pointercancel', end);
  });
  function onViewport() { apply(); save(); }
  window.addEventListener('resize', onViewport);
  apply();
  chrome.storage.local.get('panelSettings').then(({panelSettings}) => {
    if (!host.isConnected) return;
    if (panelSettings && typeof panelSettings === 'object') {
      for (const key of Object.keys(settings)) if (Number.isFinite(panelSettings[key])) settings[key] = panelSettings[key];
      settings.scale = clamp(settings.scale,75,200); settings.opacity = clamp(settings.opacity,25,100);
    }
    apply(); ready = true;
    observer = new ResizeObserver(() => {
      if (!ready || !host.isConnected) return;
      const bounds = box.getBoundingClientRect();
      if (Math.abs(bounds.width - settings.width) < 1 && Math.abs(bounds.height - settings.height) < 1) return;
      settings.width = bounds.width; settings.height = bounds.height; apply(); save();
    });
    observer.observe(box);
  }).catch(() => {});
  async function update() {
    if (!host.isConnected) { cleanup(); return; }
    try {
      const result = await chrome.runtime.sendMessage({type: 'answer'});
      if (!host.isConnected) { cleanup(); return; }
      content.textContent = result?.text || ''; status.textContent = result?.state || 'Ожидание';
    } catch { status.textContent = 'Расширение обновлено — откройте панель заново'; cleanup(); return; }
    if (!stopped) timer = setTimeout(update, 1500);
  }
  update();
})();
