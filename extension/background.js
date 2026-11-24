chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.type !== 'answer') return;
  (async () => {
    const {bridgeKey} = await chrome.storage.local.get('bridgeKey');
    if (!bridgeKey) return {state: 'Введите ключ в настройках расширения', text: ''};
    try {
      const response = await fetch('http://127.0.0.1:17843/answer', {
        headers: {'X-Bridge-Key': bridgeKey}, cache: 'no-store', signal: AbortSignal.timeout(2500)
      });
      if (!response.ok) throw new Error(String(response.status));
      return await response.json();
    } catch { return {state: 'Нет связи с приложением или неверный ключ', text: ''}; }
  })().then(respond, () => respond({state: 'Ошибка связи', text: ''}));
  return true;
});
