(function(){
  function init(){
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
    const serverModeBadge = el('serverModeBadge');

    // 편집 중 덮어쓰기 방지 플래그
    const editing = { pwm: false };
    let editingTimer = null;
    let pendingPwm = null; // Apply 전까지 사용자가 의도한 값

    const setEditing = (on)=>{
      editing.pwm = !!on;
      if (editingTimer) { clearTimeout(editingTimer); editingTimer = null; }
      if (editing.pwm) {
        editingTimer = setTimeout(()=>{
          editing.pwm = false;
          pendingPwm = null; // 편집 보류 해제
          console.log('[fan] editing timeout -> revert to server value');
          try { fetchTelemetry(); } catch(e) {}
        }, 10000); // 10s idle 후 자동 해제
      }
    };

    if (!modeSel) {
      console.warn('[fan] elements not ready yet. retry...');
      setTimeout(init, 100); return;
    }
    console.log('[fan] init OK. adminDisabled=', modeSel.hasAttribute('disabled'));

    function updateManualVisibility(){
      if (!manualWrap) return;
      const manual = modeSel.value === 'MANUAL';
      if (manual) {
        manualWrap.style.display = '';
        manualWrap.classList.remove('d-none');
      } else {
        manualWrap.style.display = 'none';
        manualWrap.classList.add('d-none');
      }
    }
    function setBadge(mode){
      if (!serverModeBadge) return;
      const m = (mode || 'AUTOMATIC').toUpperCase();
      serverModeBadge.innerHTML = `현재 서버 모드: <span class="badge ${m==='MANUAL'?'bg-warning text-dark':'bg-secondary'}">${m}</span>`;
    }

    try {
      if (window.__initialMode) {
        const m = String(window.__initialMode).toUpperCase();
        modeSel.value = m;
        setBadge(m);
        updateManualVisibility();
        if (m === 'MANUAL' && typeof window.__initialSetPwm === 'number') {
          pwmRange && (pwmRange.value = window.__initialSetPwm);
          pwmNum && (pwmNum.value = window.__initialSetPwm);
          pwmLabel && (pwmLabel.textContent = `${window.__initialSetPwm}%`);
        }
      } else {
        updateManualVisibility();
      }
    } catch (e) { console.warn('[fan] initial render error', e); updateManualVisibility(); }

    function countUp(targetEl, textBuilder, newValue, suffix){
      try{
        const curText = targetEl.textContent.trim();
        const match = curText.match(/(-?\d+)/);
        const cur = match? parseInt(match[1],10) : 0;
        const end = parseInt(newValue,10);
        if (isNaN(end)) { targetEl.textContent = textBuilder(newValue); return; }
        const steps = 8; const delta = (end - cur) / steps; let i=0; let v = cur;
        const timer = setInterval(()=>{
          v = (i===steps-1)? end : Math.round(v + delta);
          targetEl.textContent = textBuilder(v) + (suffix||'');
          if(++i>=steps) clearInterval(timer);
        }, 25);
      }catch{ targetEl.textContent = textBuilder(newValue) + (suffix||''); }
    }

    function render(data){
      if (!data) return;
      const serverMode = (data.mode || 'AUTOMATIC').toUpperCase();
      if (modeSel.value !== serverMode) {
        console.log('[fan] mode reflect from server ->', serverMode);
        modeSel.value = serverMode;
      }
      // 모드 전환 시 보류값 정리
      if (serverMode !== 'MANUAL') { pendingPwm = null; }
      updateManualVisibility();
      setBadge(serverMode);
      if (serverMode === 'MANUAL') {
        // 편집 중이거나 보류 값이 있으면 서버값으로 덮어쓰지 않음
        if (!editing.pwm && pendingPwm === null) {
          if (pwmRange) pwmRange.value = data.setPwm;
          if (pwmNum) pwmNum.value = data.setPwm;
          if (pwmLabel) pwmLabel.textContent = `${data.setPwm}%`;
        }
      }
      if (cpu) countUp(cpu, v=>`${v} °C`, data.cpuTemp);
      if (gpu) countUp(gpu, v=>`${v} °C`, data.gpuTemp);
      const label = data.model?.label ?? 'Unknown';
      const code = data.model?.code ?? -1;
      if (model) model.textContent = `${label}${code>=0 ? ` (${code})` : ''}`;
      if (setPwm) countUp(setPwm, v=>`${v} %`, data.setPwm);
      if (actualPwm) countUp(actualPwm, v=>`${v} %`, data.actualPwm);
    }

    async function fetchTelemetry(){
      try {
        const res = await fetch('/web/fan/telemetry', { headers: { 'Accept': 'application/json' } });
        if (!res.ok) {
          console.error('[fan] telemetry HTTP', res.status);
          return;
        }
        const data = await res.json();
        render(data);
      } catch(err){ console.error('[fan] telemetry error:', err); }
    }

    async function sendControl(){
      const body = { mode: modeSel.value };
      if (modeSel.value === 'MANUAL' && pwmNum) {
        const v = Number(pwmNum.value);
        body.pwm = Math.max(0, Math.min(100, isFinite(v) ? v : 0));
      }
      try {
        console.log('[fan] send control', body);
        const res = await fetch('/web/fan/control', { method:'POST', headers:{'Content-Type':'application/json','Accept':'application/json'}, body: JSON.stringify(body) });
        if (!res.ok) {
          console.error('[fan] control HTTP', res.status);
          return fetchTelemetry();
        }
        const data = await res.json();
        // 보류 해제: 서버값 반영 허용
        setEditing(false);
        pendingPwm = null;
        render(data);
      } catch(err){ console.error('[fan] control error:', err); fetchTelemetry(); }
    }

    function startSse(){
      if (!window.EventSource) return false;
      try{
        const es = new EventSource('/web/fan/stream');
        es.addEventListener('telemetry', e=>{ try{ render(JSON.parse(e.data)); }catch(e){ console.error('[fan] sse parse error', e);} });
        es.onopen = ()=>{ console.log('[fan] sse open'); fetchTelemetry(); };
        es.onmessage = e => { try{ render(JSON.parse(e.data)); }catch(e){ console.error('[fan] sse msg parse error', e);} };
        es.onerror = ()=>{ console.warn('[fan] sse error -> fallback polling'); es.close(); setTimeout(()=>startPolling(),2000); };
        return true;
      }catch(e){ console.error('[fan] sse init error', e); return false; }
    }

    function startPolling(){
      fetchTelemetry();
      window.__telemetryTimer && clearInterval(window.__telemetryTimer);
      window.__telemetryTimer = setInterval(fetchTelemetry, 3000);
    }

    function guardModeChange(){
      if (modeSel.hasAttribute('disabled')) {
        console.log('[fan] change ignored (disabled)');
        updateManualVisibility();
        return true;
      }
      return false;
    }

    // 이벤트 바인딩
    modeSel.addEventListener('change', ()=>{
      console.log('[fan] modeSel change ->', modeSel.value);
      if (guardModeChange()) return;
      // 모드 변경 시 보류값 초기화
      pendingPwm = null;
      updateManualVisibility();
      sendControl();
    });
    if (pwmRange) {
      pwmRange.addEventListener('input', ()=>{
        setEditing(true);
        if (!pwmNum||!pwmLabel) return;
        pwmNum.value = pwmRange.value;
        pwmLabel.textContent = `${pwmRange.value}%`;
        pendingPwm = Number(pwmRange.value);
      });
      // pointerup/blur/change 시 즉시 해제하지 않음
    }
    if (pwmNum) {
      pwmNum.addEventListener('input', ()=>{
        setEditing(true);
        if (!pwmRange||!pwmLabel) return;
        const v = Math.max(0, Math.min(100, Number(pwmNum.value)||0));
        pwmNum.value = v;
        pwmRange.value = v;
        pwmLabel.textContent = `${v}%`;
        pendingPwm = v;
      });
      // focus/blur/change로 편집 상태를 즉시 종료하지 않음
    }
    if (applyBtn) applyBtn.addEventListener('click', ()=>{ setEditing(false); sendControl(); });

    // 시작: 초기 동기화 1회 + SSE 우선, 불가 시 폴링
    fetchTelemetry();
    if(!startSse()) startPolling();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
