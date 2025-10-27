(function(){
  const el = id => document.getElementById(id);
  const cpu = el('cpuTemp');
  const gpu = el('gpuTemp');
  const model = el('modelResult');
  const setPwm = el('setPwm');
  const actualPwm = el('actualPwm');
  const modeSel = el('controlMode');
  const manualWrap = el('manualControls');
  const pwmRange = el('pwmRange');
  const pwmNum = el('pwmNumber');
  const pwmLabel = el('pwmLabel');
  const applyBtn = el('applyBtn');

  const kpiCards = document.querySelectorAll('.kpi');
  kpiCards.forEach((card,i)=>{
    card.style.opacity=0; card.style.transform='translateY(8px)';
    setTimeout(()=>{ card.style.transition='opacity .4s ease, transform .4s ease'; card.style.opacity=1; card.style.transform='translateY(0)'; }, 80*i);
  });

  function updateManualVisibility(){
    const manual = modeSel.value === 'MANUAL';
    manualWrap.style.display = manual ? '' : 'none';
  }

  function colorize(keyEl, val){
    keyEl.classList.remove('danger');
    if (keyEl === model && (val?.code === 1 || /abnormal/i.test(val?.label || ''))) {
      keyEl.classList.add('danger');
    }
  }

  function countUp(targetEl, textBuilder, newValue, suffix){
    try{
      const curText = targetEl.textContent.trim();
      const match = curText.match(/(-?\d+)/);
      const cur = match? parseInt(match[1],10) : 0;
      const end = parseInt(newValue,10);
      if (isNaN(end)) { targetEl.textContent = textBuilder(newValue); return; }
      const steps = 12; const delta = (end - cur) / steps; let i=0; let v = cur;
      const timer = setInterval(()=>{
        v = (i===steps-1)? end : Math.round(v + delta);
        targetEl.textContent = textBuilder(v) + (suffix||'');
        if(++i>=steps) clearInterval(timer);
      }, 25);
    }catch{ targetEl.textContent = textBuilder(newValue) + (suffix||''); }
  }

  function render(data){
    if (!data) return;
    countUp(cpu, v=>`${v} °C`, data.cpuTemp);
    countUp(gpu, v=>`${v} °C`, data.gpuTemp);
    const label = data.model?.label ?? 'Unknown';
    const code = data.model?.code ?? -1;
    model.textContent = `${label}${code>=0 ? ` (${code})` : ''}`;
    countUp(setPwm, v=>`${v} %`, data.setPwm);
    countUp(actualPwm, v=>`${v} %`, data.actualPwm);
    if (modeSel.value !== (data.mode || 'AUTOMATIC')) {
      modeSel.value = data.mode || 'AUTOMATIC';
      updateManualVisibility();
    }
    if (modeSel.value === 'MANUAL') {
      pwmRange.value = data.setPwm;
      pwmNum.value = data.setPwm;
      pwmLabel.textContent = `${data.setPwm}%`;
    }
    colorize(model, {code, label});
  }

  async function fetchTelemetry(){
    try {
      const res = await fetch('/web/fan/telemetry', { headers: { 'Accept': 'application/json' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      render(data);
    } catch(err){ console.warn('telemetry error:', err); }
  }

  function showToast(msg){
    const t = document.createElement('div');
    t.textContent = msg;
    t.style.cssText = 'position:fixed;right:16px;bottom:16px;background:#111827;color:#fff;padding:12px 16px;border-radius:8px;box-shadow:0 6px 16px rgba(2,6,23,.28);z-index:9999;opacity:0;transform:translateY(6px);transition:opacity .2s ease, transform .2s ease';
    document.body.appendChild(t);
    requestAnimationFrame(()=>{ t.style.opacity='1'; t.style.transform='translateY(0)'; });
    setTimeout(()=>{ t.style.opacity='0'; t.style.transform='translateY(4px)'; setTimeout(()=>t.remove(),220); }, 1500);
  }

  async function sendControl(){
    const body = { mode: modeSel.value };
    if (modeSel.value === 'MANUAL') {
      const v = Number(pwmNum.value);
      body.pwm = Math.max(0, Math.min(100, isFinite(v) ? v : 0));
    }
    try {
      const res = await fetch('/web/fan/control', { method:'POST', headers:{'Content-Type':'application/json','Accept':'application/json'}, body: JSON.stringify(body) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      render(data); showToast('설정이 적용되었습니다');
    } catch(err){ console.error('control error:', err); fetchTelemetry(); }
  }

  function startSse(){
    if (!window.EventSource) return false;
    try{ const es = new EventSource('/web/fan/stream'); es.addEventListener('telemetry', e=>{ try{ render(JSON.parse(e.data)); }catch{} }); es.onmessage=e=>{ try{ render(JSON.parse(e.data)); }catch{} }; es.onerror=()=>{ es.close(); setTimeout(()=>startPolling(),2000); }; return true; }catch{ return false; }
  }

  function startPolling(){
    fetchTelemetry();
    window.__telemetryTimer && clearInterval(window.__telemetryTimer);
    window.__telemetryTimer = setInterval(fetchTelemetry, 3000);
  }

  function isAdmin(){ try{ return (window.sessionRole||'')==='ADMIN'; }catch{return false;} }
  // 템플릿에서 session.role을 data-attr로 주입해두는 방법이 가장 정확하지만, 여기서는 disabled 속성으로도 판별
  function guardModeChange(){
    if (modeSel.hasAttribute('disabled')) {
      modeSel.value = 'AUTOMATIC';
      manualWrap.style.display = 'none';
      return true;
    }
    return false;
  }

  // 이벤트 바인딩
  modeSel.addEventListener('change', ()=>{
    if (guardModeChange()) return;
    updateManualVisibility();
    sendControl();
  });
  pwmRange.addEventListener('input', ()=>{ pwmNum.value = pwmRange.value; pwmLabel.textContent = `${pwmRange.value}%`; });
  pwmNum.addEventListener('input', ()=>{ const v = Math.max(0, Math.min(100, Number(pwmNum.value)||0)); pwmNum.value = v; pwmRange.value = v; pwmLabel.textContent = `${v}%`; });
  applyBtn.addEventListener('click', sendControl);

  updateManualVisibility();
  // 로그인 성공 파라미터시 토스트 표시
  try { const params = new URLSearchParams(window.location.search); if (params.has('login')) { showToast('환영합니다! 로그인되었습니다.'); } } catch {}
  if(!startSse()) startPolling();
})();
