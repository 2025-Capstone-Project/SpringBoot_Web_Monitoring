(function(){
  function init(){
    const el = id => document.getElementById(id);
    const cpu = el('cpuTemp');
    const gpu = el('gpuTemp');
    const model = el('modelResult');
    const setPwm = el('setPwm');
    const actualPwm = el('actualPwm');

    // 속도 제어 영역
    const modeSel = el('controlMode');
    const speedWrap = el('manualControlsSpeed');
    const pwmRange = el('pwmRange');
    const pwmNum = el('pwmNumber');
    const pwmLabel = el('pwmLabel');
    const applyBtn = el('applyBtn');

    // 온도 임계 제어 영역
    const tempWrap = el('manualControlsTemp');
    const cpuThRange = el('cpuThreshold');
    const cpuThNum = el('cpuThresholdNum');
    const gpuThRange = el('gpuThreshold');
    const gpuThNum = el('gpuThresholdNum');
    const applyTempBtn = el('applyTempBtn');

    const serverModeBadge = el('serverModeBadge');

    // 라디오
    const dimSpeed = document.getElementById('dimSpeed');
    const dimTemp = document.getElementById('dimTemp');
    const dimensionGroup = document.getElementById('dimensionGroup');

    // 편집 중 덮어쓰기 방지 + 보류값
    const editing = { pwm: false, temp: false, mode: false };
    let editingTimerPwm = null;
    let editingTimerTemp = null;
    let editingTimerMode = null;
    let pendingPwm = null; // Apply 전까지 사용자가 의도한 PWM
    let pendingCpu = null, pendingGpu = null; // Apply 전까지 임계치 보류값

    const setEditingPwm = (on)=>{
      editing.pwm = !!on;
      if (editingTimerPwm) { clearTimeout(editingTimerPwm); editingTimerPwm = null; }
      if (editing.pwm) {
        editingTimerPwm = setTimeout(()=>{
          editing.pwm = false;
          pendingPwm = null;
          console.log('[fan] editing PWM timeout -> keep server push');
        }, 10000);
      }
    };
    const setEditingTemp = (on)=>{
      editing.temp = !!on;
      if (editingTimerTemp) { clearTimeout(editingTimerTemp); editingTimerTemp = null; }
      if (editing.temp) {
        editingTimerTemp = setTimeout(()=>{
          editing.temp = false;
          pendingCpu = null; pendingGpu = null;
          console.log('[fan] editing TEMP timeout -> keep server push');
        }, 10000);
      }
    };
    const setEditingMode = (on)=>{
      editing.mode = !!on;
      if (editingTimerMode) { clearTimeout(editingTimerMode); editingTimerMode = null; }
      if (editing.mode) {
        editingTimerMode = setTimeout(()=>{
          editing.mode = false;
          console.log('[fan] editing MODE timeout -> allow server push to reflect mode');
        }, 10000);
      }
    };

    if (!modeSel) {
      console.warn('[fan] elements not ready yet. retry...');
      setTimeout(init, 100); return;
    }
    console.log('[fan] init OK. adminDisabled=', modeSel.hasAttribute('disabled'));

    // 표시 토글: 모드와 라디오에 따라 모든 UI를 제어
    function updateVisibility(){
      const isManual = modeSel.value === 'MANUAL';
      const isTemp = dimTemp && dimTemp.checked;

      // 라디오 그룹 활성/비활성 및 표시
      if (dimensionGroup) dimensionGroup.style.display = isManual ? '' : 'none';
      if (dimSpeed) dimSpeed.disabled = !isManual;
      if (dimTemp) dimTemp.disabled = !isManual;

      if (!isManual) {
        if (speedWrap) { speedWrap.style.display = 'none'; speedWrap.classList.add('d-none'); }
        if (tempWrap)  { tempWrap.style.display  = 'none'; tempWrap.classList.add('d-none'); }
        return;
      }
      // Manual일 때 라디오 선택에 따른 토글
      if (isTemp) {
        if (tempWrap)  { tempWrap.style.display  = '';    tempWrap.classList.remove('d-none'); }
        if (speedWrap) { speedWrap.style.display = 'none'; speedWrap.classList.add('d-none'); }
      } else {
        if (speedWrap) { speedWrap.style.display = '';    speedWrap.classList.remove('d-none'); }
        if (tempWrap)  { tempWrap.style.display  = 'none'; tempWrap.classList.add('d-none'); }
      }
    }

    function setBadge(mode){
      if (!serverModeBadge) return;
      const m = (mode || 'AUTOMATIC').toUpperCase();
      serverModeBadge.innerHTML = `현재 서버 모드: <span class="badge ${m==='MANUAL'?'bg-warning text-dark':'bg-secondary'}">${m}</span>`;
    }

    // 초기값 반영
    try {
      const sectionEl = document.getElementById('fanSection');
      const ds = sectionEl ? sectionEl.dataset : {};
      const initialMode = ds?.initialMode || '';
      const initialSetPwm = Number(ds?.initialSetPwm ?? NaN);
      const initialCpuTh = Number(ds?.initialCpuTh ?? NaN);
      const initialGpuTh = Number(ds?.initialGpuTh ?? NaN);
      if (initialMode) {
        const m = String(initialMode).toUpperCase();
        modeSel.value = m;
        setBadge(m);
      }
      if (!Number.isNaN(initialSetPwm)) {
        pwmRange && (pwmRange.value = initialSetPwm);
        pwmNum && (pwmNum.value = initialSetPwm);
        pwmLabel && (pwmLabel.textContent = `${initialSetPwm}%`);
      }
      if (!Number.isNaN(initialCpuTh)) {
        cpuThRange && (cpuThRange.value = initialCpuTh);
        cpuThNum && (cpuThNum.value = initialCpuTh);
      }
      if (!Number.isNaN(initialGpuTh)) {
        gpuThRange && (gpuThRange.value = initialGpuTh);
        gpuThNum && (gpuThNum.value = initialGpuTh);
      }
    } catch (e) { console.warn('[fan] initial render error', e); }

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
      // 서버로부터 전달된 오류 메시지가 있으면 사용자에게 보여줌
      if (data.error) {
        console.warn('[fan] server error:', data.error);
        showServerMessage(String(data.error));
        // 오류인 경우에도 계속 렌더링은 진행 (서버가 보낸 상태를 신뢰)
      }
      const serverMode = (data.mode || 'AUTOMATIC').toUpperCase();
      // 서버 푸시에 의해 사용자가 방금 변경한 모드가 즉시 덮어써지는 것을 막기 위해
      // 사용자가 모드를 변경한 직후에는 서버의 모드 반영을 잠시 무시합니다.
      if (!editing.mode && modeSel.value !== serverMode) {
        console.log('[fan] mode reflect from server ->', serverMode);
        modeSel.value = serverMode;
      } else if (editing.mode && modeSel.value === serverMode) {
        // 서버가 사용자가 요청한 동일한 모드를 확정(push)하면 편집 플래그 해제
        editing.mode = false;
        if (editingTimerMode) { clearTimeout(editingTimerMode); editingTimerMode = null; }
        console.log('[fan] server confirmed mode -> clear editing.mode');
      }
      setBadge(serverMode);

      // 속도 섹션 값 반영(편집 보호 고려)
      if (serverMode === 'MANUAL') {
        if (!editing.pwm && pendingPwm === null) {
          if (pwmRange) pwmRange.value = data.setPwm;
          if (pwmNum) pwmNum.value = data.setPwm;
          if (pwmLabel) pwmLabel.textContent = `${data.setPwm}%`;
        }
      }
      // 온도 섹션 값 반영(편집 보호 고려)
      if (!editing.temp && pendingCpu === null && pendingGpu === null) {
        if (cpuThRange) cpuThRange.value = data.cpuThreshold;
        if (cpuThNum) cpuThNum.value = data.cpuThreshold;
        if (gpuThRange) gpuThRange.value = data.gpuThreshold;
        if (gpuThNum) gpuThNum.value = data.gpuThreshold;
      }

      // 마지막에 표시 토글을 적용하여 Automatic일 때 확실히 숨김
      updateVisibility();

      if (cpu) countUp(cpu, v=>`${v} °C`, data.cpuTemp);
      if (gpu) countUp(gpu, v=>`${v} °C`, data.gpuTemp);
      const label = data.model?.label ?? 'Unknown';
      const code = data.model?.code ?? -1;
      if (model) model.textContent = `${label}${code>=0 ? ` (${code})` : ''}`;
      if (setPwm) countUp(setPwm, v=>`${v} %`, data.setPwm);
      if (actualPwm) countUp(actualPwm, v=>`${v} %`, data.actualPwm);
    }

    // HTTP/SSE/폴링 제거 → WS 전용 제어 전송
    async function sendSpeedControl(){
      const body = { dimension: 'SPEED', mode: modeSel.value };
      if (modeSel.value === 'MANUAL' && pwmNum) {
        const v = Number(pwmNum.value);
        body.pwm = Math.max(0, Math.min(100, isFinite(v) ? v : 0));
      }
      if (window.__stompClient && window.__stompConnected) {
        console.log('[fan] send control (SPEED)', body);
        setEditingMode(true);
        window.__stompClient.send('/ws/control', {}, JSON.stringify(body));
        setEditingPwm(false); pendingPwm = null;
      } else {
        console.warn('[fan] STOMP not connected. fallback to HTTP POST', body);
        try {
          setEditingMode(true);
          const r = await fetch('/api/control', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
          const j = await r.json();
          if (!j.ok) showServerMessage(j.error || 'control failed');
        } catch(e){ console.error('[fan] http control fail', e); showServerMessage('HTTP control failed'); }
        setEditingPwm(false); pendingPwm = null;
      }
    }

    async function sendTempControl(){
      const body = { dimension: 'TEMP' };
      const c = isFinite(Number(cpuThNum?.value)) ? Number(cpuThNum.value) : Number(cpuThRange?.value);
      const g = isFinite(Number(gpuThNum?.value)) ? Number(gpuThNum.value) : Number(gpuThRange?.value);
      body.cpuThreshold = Math.max(30, Math.min(100, c||0));
      body.gpuThreshold = Math.max(30, Math.min(100, g||0));
      if (window.__stompClient && window.__stompConnected) {
        console.log('[fan] send control (TEMP)', body);
        setEditingMode(true);
        window.__stompClient.send('/ws/control', {}, JSON.stringify(body));
        setEditingTemp(false); pendingCpu = null; pendingGpu = null;
      } else {
        console.warn('[fan] STOMP not connected. fallback to HTTP POST', body);
        try {
          setEditingMode(true);
          const r = await fetch('/api/control', { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
          const j = await r.json();
          if (!j.ok) showServerMessage(j.error || 'control failed');
        } catch(e){ console.error('[fan] http control fail', e); showServerMessage('HTTP control failed'); }
        setEditingTemp(false); pendingCpu = null; pendingGpu = null;
      }
    }

    function startWs(){
      if (!window.SockJS || !window.Stomp) return false;
      try {
        const sock = new SockJS('/ws-endpoint');
        const client = Stomp.over(sock);
        client.debug = ()=>{}; // quiet
        client.connect({}, ()=>{
          window.__stompClient = client; window.__stompConnected = true;
          console.log('[fan] ws connected');
          client.subscribe('/topic/telemetry', msg=>{ try{ render(JSON.parse(msg.body)); }catch(e){ console.error('ws parse', e);} });
          // 구독 직후 서버가 초기 스냅샷을 push하므로 별도 fetch 불필요
        }, (err)=>{
          console.warn('[fan] ws connect fail', err);
          window.__stompConnected = false;
          try{ client.disconnect(()=>{}); }catch{}
        });
        return true;
      } catch(e){ console.error('[fan] ws init error', e); return false; }
    }

    function guardModeChange(){
      if (modeSel.hasAttribute('disabled')) {
        console.log('[fan] change ignored (disabled)');
        updateVisibility();
        return true;
      }
      return false;
    }

    // 이벤트 바인딩
    dimSpeed && dimSpeed.addEventListener('change', updateVisibility);
    dimTemp && dimTemp.addEventListener('change', updateVisibility);

    // Accordion collapse lazy-load: Bootstrap collapse 이벤트에 바인딩하여 iframe을 로드합니다.
    try {
      ['collapseCpu','collapseGpu','collapseModel'].forEach(cid => {
        const c = document.getElementById(cid);
        if (!c) return;
        c.addEventListener('show.bs.collapse', (ev) => {
          try {
            const iframeId = 'iframe' + cid.replace('collapse','');
            const iframe = document.getElementById(iframeId);
            if (iframe && (!iframe.src || iframe.src.trim() === '')) {
              const src = iframe.getAttribute('data-src');
              if (src) {
                console.log('[fan] lazy-loading iframe', iframeId, src);
                iframe.src = src;
              }
            }
          } catch(e) { console.debug('[fan] collapse load fail', e); }
        });
      });
    } catch(e){ console.debug('[fan] accordion bind fail', e); }

    // Keyboard support: clickable KPI cards should toggle collapse on Enter/Space
    try {
      document.querySelectorAll('.clickable-accordion').forEach(card => {
        card.addEventListener('keydown', (ev) => {
          const key = ev.key || ev.keyCode;
          if (key === 'Enter' || key === ' ' || key === 'Spacebar' || key === 13 || key === 32) {
            ev.preventDefault();
            const targetSelector = card.getAttribute('data-bs-target') || card.dataset.bsTarget;
            if (targetSelector) {
              const collapseEl = document.querySelector(targetSelector);
              if (collapseEl && window.bootstrap && window.bootstrap.Collapse) {
                try {
                  const inst = bootstrap.Collapse.getOrCreateInstance(collapseEl);
                  inst.toggle();
                } catch(e) {
                  // fallback
                  card.click();
                }
              } else {
                card.click();
              }
            } else {
              card.click();
            }
          }
        });
      });
    } catch(e){ console.debug('[fan] clickable keyboard bind fail', e); }

    modeSel.addEventListener('change', ()=>{
      console.log('[fan] modeSel change ->', modeSel.value);
      if (guardModeChange()) return;
      pendingPwm = null; pendingCpu = null; pendingGpu = null;
      // UI 즉시 업데이트
      // 사용자 변경 직후 서버 푸시에 의해 덮어써지지 않도록 모드 편집 플래그 설정
      setEditingMode(true);
      // 강제 표시: 클라이언트가 곧바로 수동 컨트롤을 보여주도록 함
      try {
        if (dimensionGroup) dimensionGroup.style.display = '';
        if (modeSel.value === 'MANUAL') {
          if (dimSpeed) dimSpeed.disabled = false;
          if (dimTemp) dimTemp.disabled = false;
          // 기본 라디오가 speed이면 speed영역을 직접 보여줌
          if (dimTemp && dimTemp.checked) {
            if (tempWrap) { tempWrap.style.display = ''; tempWrap.classList.remove('d-none'); }
            if (speedWrap) { speedWrap.style.display = 'none'; speedWrap.classList.add('d-none'); }
          } else {
            if (speedWrap) { speedWrap.style.display = ''; speedWrap.classList.remove('d-none'); }
            if (tempWrap) { tempWrap.style.display = 'none'; tempWrap.classList.add('d-none'); }
          }
        } else {
          if (dimensionGroup) dimensionGroup.style.display = 'none';
          if (speedWrap) { speedWrap.style.display = 'none'; speedWrap.classList.add('d-none'); }
          if (tempWrap) { tempWrap.style.display = 'none'; tempWrap.classList.add('d-none'); }
        }
      } catch(e){ console.warn('[fan] forced visibility failed', e); }
      updateVisibility();
      sendSpeedControl();
    });

    if (pwmRange) {
      pwmRange.addEventListener('input', ()=>{
        setEditingPwm(true);
        if (!pwmNum||!pwmLabel) return;
        pwmNum.value = pwmRange.value;
        pwmLabel.textContent = `${pwmRange.value}%`;
        pendingPwm = Number(pwmRange.value);
      });
    }
    if (pwmNum) {
      pwmNum.addEventListener('input', ()=>{
        setEditingPwm(true);
        if (!pwmRange||!pwmLabel) return;
        const v = Math.max(0, Math.min(100, Number(pwmNum.value)||0));
        pwmNum.value = v;
        pwmRange.value = v;
        pwmLabel.textContent = `${v}%`;
        pendingPwm = v;
      });
    }
    if (applyBtn) applyBtn.addEventListener('click', ()=>{ setEditingPwm(false); setEditingMode(true); sendSpeedControl(); });

    const clamp = (v, lo, hi)=> Math.max(lo, Math.min(hi, v));
    function syncCpu(v){
      const val = clamp(Number(v)||0, 30, 100);
      if (cpuThRange) cpuThRange.value = val;
      if (cpuThNum) cpuThNum.value = val;
    }
    function syncGpu(v){
      const val = clamp(Number(v)||0, 30, 100);
      if (gpuThRange) gpuThRange.value = val;
      if (gpuThNum) gpuThNum.value = val;
    }

    if (cpuThRange) cpuThRange.addEventListener('input', ()=>{ setEditingTemp(true); syncCpu(cpuThRange.value); pendingCpu = Number(cpuThRange.value); });
    if (cpuThNum)   cpuThNum.addEventListener('input',   ()=>{ setEditingTemp(true); syncCpu(cpuThNum.value);   pendingCpu = Number(cpuThNum.value);   });
    if (gpuThRange) gpuThRange.addEventListener('input', ()=>{ setEditingTemp(true); syncGpu(gpuThRange.value); pendingGpu = Number(gpuThRange.value); });
    if (gpuThNum)   gpuThNum.addEventListener('input',   ()=>{ setEditingTemp(true); syncGpu(gpuThNum.value);   pendingGpu = Number(gpuThNum.value);   });

    if (applyTempBtn) applyTempBtn.addEventListener('click', ()=>{ setEditingTemp(false); setEditingMode(true); sendTempControl(); });

    // 시작: 섹션 토글 후 WS 연결만 시도
    updateVisibility();
    if(!startWs()){
      console.error('[fan] WebSocket 연결 실패. 서버 엔드포인트 및 보안 설정을 확인하세요.');
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
