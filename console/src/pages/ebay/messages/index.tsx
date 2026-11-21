import {Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message} from 'antd';
import {MinusCircleOutlined, PlusOutlined} from '@ant-design/icons';
import React, {useEffect, useState} from 'react';
import moment from 'moment';
import {doGetRequest, doPostRequest} from '@/util/http';
import {EBAY_MESSAGE_API, EBAY_MESSAGE_STATUS} from '@/services/ebayMessage';
import type {EbayMemberMessage} from '@/services/ebayMessage';
import {notifyMessage, SOUND_KEY, soundEnabled} from '@/components/EbayMessageNotifier';

const {Text, Paragraph} = Typography;
const MAX_REPLY = 2000;

const formatTime = (value?: string) => (value ? moment(value).format('YYYY-MM-DD HH:mm:ss') : '-');

const EbayMessagesPage = () => {
    const [form] = Form.useForm();
    const [status, setStatus] = useState<any>({});
    const [devIdConfigured, setDevIdConfigured] = useState(false);
    const [saving, setSaving] = useState(false);
    const [messages, setMessages] = useState<EbayMemberMessage[]>([]);
    const [statusFilter, setStatusFilter] = useState<string>();
    const [loading, setLoading] = useState(false);
    const [sound, setSound] = useState(soundEnabled());
    const [permission, setPermission] = useState(
        'Notification' in window ? Notification.permission : 'unsupported');

    const loadSettings = () => {
        doGetRequest(EBAY_MESSAGE_API.SETTINGS, {}, {
            onSuccess: res => {
                setDevIdConfigured(res.data.devIdConfigured);
                form.setFieldsValue({...res.data, devId: ''});
            },
            onError: res => message.error(res.errorMsg || '配置加载失败'),
        });
    };
    const loadStatus = () => doGetRequest(EBAY_MESSAGE_API.STATUS, {}, {onSuccess: res => setStatus(res.data)});
    const loadMessages = () => {
        setLoading(true);
        doGetRequest(EBAY_MESSAGE_API.LIST, {status: statusFilter, limit: 200}, {
            onSuccess: res => setMessages(res.data || []),
            onError: res => message.error(res.errorMsg || '消息加载失败'),
            onFinally: () => setLoading(false),
        });
    };

    useEffect(() => {
        loadSettings();
        loadStatus();
    }, []);
    useEffect(loadMessages, [statusFilter]);

    const save = (values: any) => {
        setSaving(true);
        doPostRequest(EBAY_MESSAGE_API.SETTINGS, {...values, rules: values.rules || []}, {
            onSuccess: res => {
                message.success('已保存');
                setDevIdConfigured(res.data.devIdConfigured);
                form.setFieldsValue({...res.data, devId: ''});
                loadStatus();
            },
            onError: res => message.error(res.errorMsg || '保存失败'),
            onFinally: () => setSaving(false),
        });
    };

    const action = (api: string, confirmText: string, onSuccess: (res: any) => void) => {
        Modal.confirm({
            title: confirmText,
            onOk: () => new Promise<void>(resolve => doPostRequest(api, {}, {
                onSuccess,
                onError: res => message.error(res.errorMsg || '操作失败'),
                onFinally: resolve,
            })),
        });
    };

    const showPreferences = () => {
        doGetRequest(EBAY_MESSAGE_API.PREFERENCES, {}, {
            onSuccess: res => Modal.info({
                title: 'eBay 推送配置',
                width: 720,
                content: <pre style={{maxHeight: 480, overflow: 'auto'}}>{JSON.stringify(res.data, null, 2)}</pre>,
            }),
            onError: res => message.error(res.errorMsg || '查询失败'),
        });
    };

    const testAlert = async () => {
        if (permission === 'default') {
            setPermission(await Notification.requestPermission());
        }
        notifyMessage({senderId: 'test_buyer', body: '这是一条测试提醒', status: 'manual'});
    };

    const toggleSound = (on: boolean) => {
        localStorage.setItem(SOUND_KEY, on ? 'on' : 'off');
        setSound(on);
    };

    const columns = [
        {title: '时间', dataIndex: 'messageTime', width: 170, render: (v: string, r: EbayMemberMessage) => formatTime(v || r.gmtCreate)},
        {
            title: '状态', dataIndex: 'status', width: 110,
            render: (v: string) => <Tag color={EBAY_MESSAGE_STATUS[v]?.color}>{EBAY_MESSAGE_STATUS[v]?.label || v}</Tag>,
        },
        {title: '买家', dataIndex: 'senderId', width: 130},
        {
            title: '商品', dataIndex: 'itemTitle', width: 240,
            render: (v: string, r: EbayMemberMessage) =>
                <a href={`https://www.ebay.com/itm/${r.itemId}`} target="_blank" rel="noreferrer">{v || r.itemId}</a>,
        },
        {
            title: '提问', dataIndex: 'body',
            render: (v: string, r: EbayMemberMessage) => <Paragraph ellipsis={{rows: 3, expandable: true}} style={{marginBottom: 0}}>{v || r.subject}</Paragraph>,
        },
        {
            title: '处理', dataIndex: 'reply', width: 260,
            render: (v: string, r: EbayMemberMessage) => (
                <Space direction="vertical" size={0}>
                    {r.matchedRule && <Text type="secondary">规则：{r.matchedRule}</Text>}
                    {v && <Paragraph ellipsis={{rows: 2, expandable: true}} style={{marginBottom: 0}}>{v}</Paragraph>}
                    {r.error && <Text type="warning">{r.error}</Text>}
                </Space>
            ),
        },
    ];

    return (
        <Space direction="vertical" size="middle" style={{width: '100%'}}>
            <Card title="eBay 买家提问自动回复" extra={
                <Space wrap>
                    <Button onClick={showPreferences}>查看推送配置</Button>
                    <Button onClick={() => action(EBAY_MESSAGE_API.SUBSCRIBE, '向 eBay 订阅买家提问推送到当前推送地址？',
                        () => message.success('订阅成功'))}>订阅推送</Button>
                    <Button onClick={() => action(EBAY_MESSAGE_API.RECONCILE, '立即拉取 eBay 未回复提问并补处理？',
                        res => { message.success(`对账完成，新处理 ${res.data} 条`); loadMessages(); loadStatus(); })}>立即对账</Button>
                </Space>
            }>
                <Space wrap size="large" style={{marginBottom: 16}}>
                    <Text>今日消息调用：{status.usedCallsToday ?? '-'} / {status.dailyCallLimit ?? '-'}</Text>
                    <Text>上次对账：{status.lastReconcile ? formatTime(status.lastReconcile) : '本次启动后未执行'}</Text>
                    <Space>
                        <Text>提示音</Text>
                        <Switch checked={sound} onChange={toggleSound} />
                    </Space>
                    <Text>浏览器通知：{permission === 'granted' ? '已授权' : permission === 'denied' ? '已拒绝（需在浏览器设置中开启）' : permission === 'unsupported' ? '不支持' : '未授权'}</Text>
                    <Button size="small" onClick={testAlert}>测试提醒</Button>
                </Space>
                <Alert type="info" showIcon style={{marginBottom: 16}}
                       message="建议先开启功能、关闭自动回复，观察几天命中情况再打开自动回复。提示音需要先在页面上任意点击一次才能播放。" />
                <Form form={form} layout="vertical" onFinish={save}>
                    <Space wrap size="large">
                        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
                        <Form.Item name="autoReply" label="自动回复（关闭则只记录、全部转人工）" valuePropName="checked"><Switch /></Form.Item>
                        <Form.Item name="dailyCallLimit" label="每日消息调用上限" rules={[{required: true}]}>
                            <InputNumber min={0} max={5000} />
                        </Form.Item>
                        <Form.Item name="reconcileIntervalMinutes" label="对账间隔（分钟）" rules={[{required: true}]}>
                            <InputNumber min={30} max={10080} />
                        </Form.Item>
                        <Form.Item name="reconcileDays" label="对账回溯天数" rules={[{required: true}]}>
                            <InputNumber min={1} max={30} />
                        </Form.Item>
                    </Space>
                    <Space wrap size="large" style={{width: '100%'}}>
                        <Form.Item name="deliveryUrl" label="推送地址" style={{width: 480}}>
                            <Input placeholder="https://shoes.ken-chy129.cn/api/ebay/notifications/trading" />
                        </Form.Item>
                        <Form.Item name="devId" label={`Dev ID（${devIdConfigured ? '已配置，留空不修改' : '未配置'}）`} style={{width: 360}}>
                            <Input.Password autoComplete="new-password" placeholder={devIdConfigured ? '••••••••' : 'eBay Production Dev ID'} />
                        </Form.Item>
                    </Space>
                    <Text strong>回复规则（按顺序匹配，第一条命中的生效；关键词不区分大小写、按整词匹配）</Text>
                    <Form.List name="rules">
                        {(fields, {add, remove, move}) => (
                            <Space direction="vertical" style={{width: '100%', marginTop: 8}}>
                                {fields.map(({key, name}, index) => (
                                    <Card size="small" key={key} title={`规则 ${index + 1}`} extra={
                                        <Space>
                                            <Button size="small" disabled={index === 0} onClick={() => move(index, index - 1)}>上移</Button>
                                            <Button size="small" danger icon={<MinusCircleOutlined />} onClick={() => remove(name)}>删除</Button>
                                        </Space>
                                    }>
                                        <Space wrap align="start" style={{width: '100%'}}>
                                            <Form.Item name={[name, 'name']} label="名称" rules={[{required: true, message: '请填写名称'}]}>
                                                <Input style={{width: 160}} />
                                            </Form.Item>
                                            <Form.Item name={[name, 'keywords']} label="关键词（回车添加）"
                                                       rules={[{required: true, message: '至少一个关键词'}]}>
                                                <Select mode="tags" open={false} tokenSeparators={[',', '，']} style={{width: 360}} />
                                            </Form.Item>
                                        </Space>
                                        <Form.Item name={[name, 'reply']} label="回复内容"
                                                   rules={[{required: true, message: '请填写回复'}, {max: MAX_REPLY}]}>
                                            <Input.TextArea rows={3} showCount maxLength={MAX_REPLY} />
                                        </Form.Item>
                                    </Card>
                                ))}
                                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({name: '', keywords: [], reply: ''})}>添加规则</Button>
                            </Space>
                        )}
                    </Form.List>
                    <Button type="primary" htmlType="submit" loading={saving} style={{marginTop: 16}}>保存配置</Button>
                </Form>
            </Card>
            <Card title="买家提问记录" extra={
                <Space>
                    <Select allowClear placeholder="全部状态" style={{width: 140}} value={statusFilter} onChange={setStatusFilter}
                            options={Object.entries(EBAY_MESSAGE_STATUS).map(([value, s]) => ({value, label: s.label}))} />
                    <Button onClick={loadMessages}>刷新</Button>
                </Space>
            }>
                <Table rowKey="messageId" size="small" loading={loading} dataSource={messages} columns={columns}
                       pagination={{pageSize: 20}} />
            </Card>
        </Space>
    );
};

export default EbayMessagesPage;
