const key = document.getElementById('key');
const status = document.getElementById('status');
chrome.storage.local.get('bridgeKey').then(value => { key.value = value.bridgeKey || ''; });
document.getElementById('save').onclick = async () => {
  await chrome.storage.local.set({bridgeKey: key.value.trim()}); status.textContent = 'Ключ сохранён.';
};
document.getElementById('toggle').onclick = async () => {
  try {
    const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
    await chrome.scripting.executeScript({target: {tabId: tab.id}, files: ['panel.js']});
    window.close();
  } catch { status.textContent = 'Откройте обычную веб-страницу: служебные страницы Chrome не поддерживаются.'; }
};
