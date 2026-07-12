/**
 * 流式跟踪前端渲染器——独立零依赖，可嵌入任何页面.
 *
 * 用法:
 *   // 自动创建 WebSocket 连接
 *   initStreamTracker("container-id", "ws://localhost:8080/ws/projects/5");
 *
 *   // 复用已有 WebSocket
 *   var ws = new WebSocket('ws://localhost:8080/ws/projects/5');
 *   initStreamTracker("container-id", ws);
 *
 * 支持事件:
 *   - STEP_START  → 创建步骤卡片
 *   - STEP_UPDATE → 流式追加内容（打字机效果）
 *   - STEP_END    → 标记步骤完成
 */

function initStreamTracker(containerId, wsUrlOrWebSocket) {
    var container = document.getElementById(containerId);
    if (!container) {
        console.error('StreamTracker: container #' + containerId + ' not found');
        return;
    }

    // 确保容器样式（不清空现有内容）
    container.classList.add('st-container');

    var cards = {};          // stepId → card DOM
    var accumulators = {};   // stepId → accumulated text for the card
    var ws = null;
    var onStepEndCallback = null;

    // ---------- WebSocket 连接 ----------
    if (typeof wsUrlOrWebSocket === 'string') {
        ws = new WebSocket(wsUrlOrWebSocket);
    } else {
        ws = wsUrlOrWebSocket;  // 复用已有 WebSocket
    }

    ws.addEventListener('open', function() {
        console.log('StreamTracker WebSocket connected');
    });

    ws.addEventListener('message', function(e) {
        try {
            var msg = JSON.parse(e.data);
            var eventName = msg.event;
            var eventDataRaw = msg.data;

            // 只处理 track 事件，其他事件忽略
            if (eventName !== 'track') return;

            var evt;
            try {
                evt = (typeof eventDataRaw === 'string') ? JSON.parse(eventDataRaw) : eventDataRaw;
            } catch(ex) {
                console.error('StreamTracker parse error:', ex);
                return;
            }

            switch (evt.type) {
                case 'STEP_START':
                    console.log('StreamTracker STEP_START:', evt.stepId, evt.title);
                    handleStart(evt);
                    break;
                case 'STEP_UPDATE':
                    console.log('StreamTracker STEP_UPDATE:', evt.stepId, 'chunk length:', evt.content ? evt.content.length : 0);
                    handleUpdate(evt);
                    break;
                case 'STEP_END':
                    console.log('StreamTracker STEP_END:', evt.stepId, evt.status);
                    handleEnd(evt);
                    break;
                default:
                    console.warn('StreamTracker unknown event type:', evt.type);
            }
        } catch (err) {
            console.error('StreamTracker message error:', err);
        }
    });

    ws.addEventListener('error', function() {
        console.warn('StreamTracker WebSocket error');
    });

    ws.addEventListener('close', function() {
        console.log('StreamTracker WebSocket disconnected');
    });

    // ---------- 事件处理 ----------

    function handleStart(evt) {
        if (cards[evt.stepId]) return; // 已存在

        // 步骤状态指示器（父级 UI）
        var indicator = document.getElementById('st-indicator-' + evt.stepId);
        if (indicator) {
            indicator.classList.add('st-running');
            var dot = indicator.querySelector('.st-dot');
            if (dot) dot.classList.add('st-pulse');
        }

        // 创建卡片
        var card = document.createElement('div');
        card.className = 'st-card';
        card.id = 'st-card-' + evt.stepId;
        card.innerHTML =
            '<div class="st-card-header" onclick="this.parentElement.classList.toggle(\'st-collapsed\')">' +
            '  <span class="st-icon st-icon-running">&#9679;</span>' +
            '  <span class="st-title">' + escapeHtml(evt.title || evt.stepId) + '</span>' +
            '  <span class="st-status st-status-running">运行中</span>' +
            '  <span class="st-arrow">&#9660;</span>' +
            '</div>' +
            '<div class="st-card-body"><div class="st-content"></div></div>';
        container.appendChild(card);
        cards[evt.stepId] = card;
        accumulators[evt.stepId] = '';
        container.scrollTop = container.scrollHeight;
    }

    function handleUpdate(evt) {
        var card = cards[evt.stepId];
        var stepKey = evt.stepId;

        if (!card) {
            // 自动创建卡片（如果 STEP_START 没到但 UPDATE 先到）
            handleStart({ stepId: stepKey, title: stepKey, type: 'STEP_START' });
            card = cards[stepKey];
            if (!card) return;
        }

        // 展开卡片
        card.classList.remove('st-collapsed');

        if (!accumulators[stepKey]) accumulators[stepKey] = '';
        accumulators[stepKey] += evt.content || '';

        var contentDiv = card.querySelector('.st-content');
        if (contentDiv) {
            contentDiv.innerHTML = renderMarkdown(accumulators[stepKey]) +
                '<span class="st-cursor"></span>';
        }
        container.scrollTop = container.scrollHeight;
    }

    function handleEnd(evt) {
        var card = cards[evt.stepId];
        if (!card) return;

        // 清除光标
        var contentDiv = card.querySelector('.st-content');
        if (contentDiv && accumulators[evt.stepId]) {
            contentDiv.innerHTML = renderMarkdown(accumulators[evt.stepId]);
        }

        // 更新状态指示器
        var statusEl = card.querySelector('.st-status');
        var iconEl = card.querySelector('.st-icon');
        if (statusEl) {
            if (evt.status === 'failed') {
                statusEl.textContent = '失败';
                statusEl.className = 'st-status st-status-failed';
                if (iconEl) { iconEl.className = 'st-icon st-icon-failed'; iconEl.innerHTML = '&#10007;'; }
            } else {
                statusEl.textContent = '完成';
                statusEl.className = 'st-status st-status-completed';
                if (iconEl) { iconEl.className = 'st-icon st-icon-completed'; iconEl.innerHTML = '&#10003;'; }
            }
        }

        // 更新父级步骤指示器
        var indicator = document.getElementById('st-indicator-' + evt.stepId);
        if (indicator) {
            indicator.classList.remove('st-running');
            indicator.classList.add(evt.status === 'failed' ? 'st-failed' : 'st-completed');
            var dot = indicator.querySelector('.st-dot');
            if (dot) dot.classList.remove('st-pulse');
        }

        if (onStepEndCallback) onStepEndCallback(evt);
    }

    // ---------- 公开 API ----------

    return {
        getWebSocket: function() { return ws; },
        onStepEnd: function(cb) { onStepEndCallback = cb; },
        disconnect: function() {
            if (ws) ws.close();
        }
    };
}

function escapeHtml(str) {
    if (!str) return '';
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}
