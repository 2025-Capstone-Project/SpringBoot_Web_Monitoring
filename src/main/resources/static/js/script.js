// 페이지 로드 시 실행
document.addEventListener('DOMContentLoaded', function() {
    console.log('서버 모니터링 시스템 웹페이지입니다.');

    // 현재 활성화된 네비게이션 링크에 active 클래스 추가
    const currentPath = window.location.pathname;
    const navLinks = document.querySelectorAll('.navbar-nav .nav-link');

    navLinks.forEach(link => {
        const href = link.getAttribute('href');
        if (href === currentPath || (href !== '/' && currentPath.startsWith(href))) {
            link.classList.add('active');
        }
    });

    // 폼 유효성 검사
    const forms = document.querySelectorAll('form');
    forms.forEach(form => {
        form.addEventListener('submit', function(event) {
            if (!form.checkValidity()) {
                event.preventDefault();
                event.stopPropagation();
            }
            form.classList.add('was-validated');
        });
    });

    // ===== WebSocket (STOMP) 연결 =====
    try {
        const sock = new SockJS('/ws');
        const client = Stomp.over(sock);
        // 개발 시 콘솔 노이즈 줄이기
        client.debug = function(){};
        window.__stompClient = client;

        client.connect({}, function() {
            console.log('[ws] connected');
            // 테스트 채널
            client.subscribe('/topic/echo', msg => console.log('[ws] echo', JSON.parse(msg.body)));
            // Influx에서 스케줄러로 밀어주는 텔레메트리 구독
            client.subscribe('/topic/telemetry', msg => {
                try {
                    const data = JSON.parse(msg.body);
                    renderBottomTicker(data);
                } catch(e) { console.warn('[ws] telemetry parse', e); }
            });
        }, function(err){
            console.warn('[ws] disconnect', err);
        });

        // 버튼 클릭으로 echo 전송(예: 특정 값 클릭 시)
        document.querySelectorAll('[data-ws-echo]').forEach(btn => {
            btn.addEventListener('click', () => {
                const payload = { ts: Date.now(), label: btn.getAttribute('data-ws-echo') };
                client.connected && client.send('/app/echo', {}, JSON.stringify(payload));
            });
        });
    } catch (e) {
        console.warn('[ws] init error', e);
    }

    // ===== KPI -> Accordion 연결 및 Grafana iframe lazy-load =====
    (function wireAccordionKpis(){
        const kpis = document.querySelectorAll('.clickable-accordion');
        if (!kpis || kpis.length === 0) {
            console.debug('[kpi] no clickable-accordion elements found');
            return;
        }
        console.debug('[kpi] found', kpis.length, 'clickable items');

        kpis.forEach(el => {
            // data-bs-target may be like "#collapseCpu"
            const target = el.getAttribute('data-bs-target') || ('#' + (el.getAttribute('aria-controls') || ''));
            if (!target) return;
            const collapseEl = document.querySelector(target);
            if (!collapseEl) {
                console.debug('[kpi] collapse target not found for', target);
                return;
            }

            // 키보드 접근성: Enter/Space로도 열리게 함
            el.addEventListener('keydown', (ev) => {
                if (ev.key === 'Enter' || ev.key === ' ') {
                    ev.preventDefault();
                    el.click();
                }
            });

            el.addEventListener('click', () => {
                // Bootstrap 5 Collapse toggle
                try {
                    // If collapse is hidden, show it; otherwise hide
                    const isShown = collapseEl.classList.contains('show');
                    const bsCollapse = bootstrap.Collapse.getOrCreateInstance(collapseEl, {toggle:false});
                    if (isShown) bsCollapse.hide(); else bsCollapse.show();
                } catch(err) {
                    console.warn('[kpi] bootstrap collapse error', err);
                }
            });

            // lazy-load iframe inside collapse when it is shown
            collapseEl.addEventListener('shown.bs.collapse', () => {
                const iframe = collapseEl.querySelector('iframe');
                if (iframe) {
                    const dataSrc = iframe.getAttribute('data-src');
                    // if data-src is set and src not yet assigned, set it
                    if (dataSrc && (!iframe.getAttribute('src') || iframe.getAttribute('src').trim() === '')) {
                        iframe.setAttribute('src', dataSrc);
                        console.debug('[kpi] lazy-loaded iframe for', target, dataSrc);
                    }
                }
            });
        });
    })();

    // ===== 하단 티커(애니메이션) =====
    function ensureBottomPanel(){
        let panel = document.getElementById('bottomTicker');
        if (!panel) {
            panel = document.createElement('div');
            panel.id = 'bottomTicker';
            panel.style.position = 'fixed';
            panel.style.left = '0';
            panel.style.right = '0';
            panel.style.bottom = '0';
            panel.style.zIndex = '1030';
            panel.style.background = 'rgba(20,22,26,0.9)';
            panel.style.color = '#fff';
            panel.style.fontSize = '14px';
            panel.style.padding = '8px 12px';
            panel.style.transform = 'translateY(100%)';
            panel.style.transition = 'transform 300ms ease-out';
            document.body.appendChild(panel);
        }
        return panel;
    }
    let hideTimer = null;
    function renderBottomTicker(data){
        const p = ensureBottomPanel();
        const parts = [];
        Object.entries(data||{}).forEach(([k,v])=>{
            parts.push(`${k}: ${v}`);
        });
        p.textContent = parts.join(' | ');
        requestAnimationFrame(()=>{ p.style.transform = 'translateY(0)'; });
        clearTimeout(hideTimer);
        hideTimer = setTimeout(()=>{ p.style.transform = 'translateY(100%)'; }, 5000);
    }
});
