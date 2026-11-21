import {useEffect} from 'react';
import {history} from '@umijs/max';
import {notification} from 'antd';
import {request} from '@@/exports';
import {EBAY_MESSAGE_API, EBAY_MESSAGE_STATUS} from '@/services/ebayMessage';
import type {EbayMemberMessage} from '@/services/ebayMessage';

const POLL_MS = 20000;
// processing 一般几秒内就会变成最终状态；超过这个时间仍未结束也照样提醒，避免卡住提醒
const PROCESSING_GRACE_MS = 2 * 60 * 1000;
export const SOUND_KEY = 'ebayMessageSound';

let audioContext: AudioContext | undefined;

/** 浏览器要求用户交互后才能出声，所以在第一次点击时创建/恢复 AudioContext。 */
const unlockAudio = () => {
    try {
        audioContext = audioContext || new AudioContext();
        if (audioContext.state === 'suspended') audioContext.resume();
    } catch (e) {
        // 不支持 Web Audio 时静默
    }
};

export const soundEnabled = () => localStorage.getItem(SOUND_KEY) !== 'off';

/** 两声短促提示音，不需要音频文件。 */
export const playChime = () => {
    if (!soundEnabled() || !audioContext) return;
    const ctx = audioContext;
    [0, 0.25].forEach((offset, i) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.frequency.value = i === 0 ? 880 : 1320;
        gain.gain.setValueAtTime(0.0001, ctx.currentTime + offset);
        gain.gain.exponentialRampToValueAtTime(0.3, ctx.currentTime + offset + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + offset + 0.2);
        osc.connect(gain).connect(ctx.destination);
        osc.start(ctx.currentTime + offset);
        osc.stop(ctx.currentTime + offset + 0.22);
    });
};

export const notifyMessage = (msg: Pick<EbayMemberMessage, 'senderId' | 'subject' | 'body' | 'status' | 'itemTitle'>) => {
    const status = EBAY_MESSAGE_STATUS[msg.status]?.label || '新消息';
    const title = `eBay买家提问 · ${status}`;
    const text = `${msg.senderId}：${msg.body || msg.subject || ''}`.slice(0, 200);
    notification.open({
        message: title,
        description: text,
        duration: msg.status === 'manual' || msg.status === 'failed' ? 0 : 8,
        onClick: () => history.push('/ebay/messages'),
    });
    if ('Notification' in window && Notification.permission === 'granted') {
        const n = new Notification(title, {body: text, tag: 'ebay-message'});
        n.onclick = () => {
            window.focus();
            history.push('/ebay/messages');
        };
    }
    playChime();
};

/** 全局轮询本地库里的新买家提问（不消耗 eBay 额度），弹页面通知、系统通知和提示音。 */
const EbayMessageNotifier = () => {
    useEffect(() => {
        document.addEventListener('click', unlockAudio);
        // 游标用服务端返回的 gmtCreate，避免浏览器时钟或时区与服务端不一致
        let cursor: number | undefined;
        const seen = new Set<string>();
        let stopped = false;
        let timer: ReturnType<typeof setTimeout>;

        const poll = async () => {
            try {
                if (!sessionStorage.getItem('token') || history.location.pathname === '/user/login') return;
                if (cursor === undefined) {
                    const res = await request(EBAY_MESSAGE_API.LIST, {method: 'GET', params: {limit: 1}});
                    if (!res?.success) return;
                    const newest: EbayMemberMessage | undefined = res.data?.[0];
                    cursor = newest ? new Date(newest.gmtCreate).getTime() : 0;
                    if (newest && newest.status !== 'processing') seen.add(newest.messageId);
                    return;
                }
                const res = await request(EBAY_MESSAGE_API.LATEST, {method: 'GET', params: {after: cursor}});
                if (!res?.success) return;
                for (const msg of (res.data || []) as EbayMemberMessage[]) {
                    const created = new Date(msg.gmtCreate).getTime();
                    // 还在处理中：先不提醒也不推进游标，下次拿到最终状态再提醒
                    if (msg.status === 'processing' && Date.now() - created < PROCESSING_GRACE_MS) break;
                    cursor = created;
                    if (seen.has(msg.messageId)) continue;
                    seen.add(msg.messageId);
                    notifyMessage(msg);
                }
            } catch (e) {
                // 网络抖动时下次再试
            } finally {
                if (!stopped) timer = setTimeout(poll, POLL_MS);
            }
        };
        timer = setTimeout(poll, 3000);
        return () => {
            stopped = true;
            clearTimeout(timer);
            document.removeEventListener('click', unlockAudio);
        };
    }, []);
    return null;
};

export default EbayMessageNotifier;
