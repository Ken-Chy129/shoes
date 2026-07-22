import {
    Alert, Button, Card, DatePicker, Form, Input, InputNumber, message, Modal, Popconfirm,
    Radio, Select, Space, Table, Tooltip, Upload, Switch, Tag, Badge, Divider,
} from "antd";
import {PlusOutlined, RedoOutlined, UploadOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest, doUploadRequestWithParams} from "@/util/http";
import {TASK_API, TASK_TYPE} from "@/services/task";
import {SETTING_API} from "@/services/shoes";
import moment from "moment";
import TaskItemModal from "../components/TaskItemModal";
import {STOCKX_ORDER_TYPE_OPTIONS, STOCKX_TASK_OPTIONS, TASK_TYPE_LABELS} from "./taskOptions";
import TaskOperationCounts from "./TaskOperationCounts";

interface TaskRecord {
    id: string;
    platform: string;
    taskType: string;
    accountName: string;
    params: string;
    startTime: string;
    endTime: string;
    cost: string;
    status: string;
    failReason: string;
    round: number;
    attributes: string;
    priceDownCount: number;
    listingCount: number;
    delistCount: number;
    pendingOperationCount: number;
    rerunnable: boolean;
}

interface StockXRateStatus {
    accountName: string;
    mode: 'BULK_ACTIVE' | 'SINGLE_FALLBACK' | 'BULK_RECOVERING' | 'GLOBAL_COOLDOWN';
    nextBatchProbeAt: number;
    nextGlobalProbeAt: number;
    currentBulkBatchSize: number;
    bulkRequestCount: number;
    bulkItemCount: number;
    singleRequestCount: number;
    singleItemCount: number;
    batchRateLimitCount: number;
    generalRateLimitCount: number;
    noResponseCount: number;
    probeAttemptCount: number;
    probeSuccessCount: number;
    confirmedPriceUpdateCount: number;
    lastSignal?: string;
    lastRateLimitAt: number;
}

const SORT_OPTIONS = [
    {label: '精选', value: 'featured'},
    {label: 'Top Selling', value: 'most-active'},
    {label: 'Price: Low to High', value: 'lowest_ask'},
    {label: '出价: 从高到低', value: 'highest_bid'},
    {label: 'Recent Price Drops', value: 'recent_asks'},
    {label: 'Total Sold: High to Low', value: 'deadstock_sold'},
    {label: '发布日期', value: 'release_date'},
    {label: 'Last Sale: High to Low', value: 'last_sale'},
];

const TaskPage = () => {
    const [conditionForm] = Form.useForm();
    const [taskList, setTaskList] = useState<TaskRecord[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);
    const [stockxRateStatus, setStockxRateStatus] = useState<StockXRateStatus[]>([]);

    const [taskItemModalVisible, setTaskItemModalVisible] = useState(false);
    const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
    const [selectedTaskRecord, setSelectedTaskRecord] = useState<TaskRecord | null>(null);

    const [paramsModalVisible, setParamsModalVisible] = useState(false);
    const [paramsModalData, setParamsModalData] = useState<Record<string, any> | null>(null);

    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewData, setPreviewData] = useState<any[]>([]);
    const [previewTitle, setPreviewTitle] = useState('');
    const [previewColumns, setPreviewColumns] = useState<any[]>([]);

    // 新建任务
    const [createModalVisible, setCreateModalVisible] = useState(false);
    const [createForm] = Form.useForm();
    const [createPlatform, setCreatePlatform] = useState<string>('stockx');
    const [createTaskType, setCreateTaskType] = useState<string>('price_down');
    const [stockxAccounts, setStockxAccounts] = useState<any[]>([]);
    const [creating, setCreating] = useState(false);

    useEffect(() => { queryTaskList(); }, [pageIndex, pageSize]);

    useEffect(() => {
        const shouldPoll = taskList.some(t =>
            t.status === 'running' || t.status === '运行中' || (t.pendingOperationCount || 0) > 0
        ) || stockxRateStatus.some(s => s.mode !== 'BULK_ACTIVE');
        if (!shouldPoll) return;
        const timer = setInterval(queryTaskList, 5000);
        return () => clearInterval(timer);
    }, [taskList, stockxRateStatus]);

    const queryTaskList = () => {
        let startTime = conditionForm.getFieldValue("startTime");
        if (startTime) startTime = moment(startTime).format('YYYY-MM-DD HH:mm:ss');
        let endTime = conditionForm.getFieldValue("endTime");
        if (endTime) endTime = moment(endTime).format('YYYY-MM-DD HH:mm:ss');
        const taskType = conditionForm.getFieldValue("taskType");
        const platform = conditionForm.getFieldValue("platform");
        const status = conditionForm.getFieldValue("status");
        doGetRequest(TASK_API.PAGE, {taskType, platform, startTime, endTime, status, pageIndex, pageSize}, {
            onSuccess: res => {
                const data = res.data || [];
                setTaskList(data);
                setTotal(res.total || 0);
                if (selectedTaskId) {
                    const updated = data.find((t: TaskRecord) => t.id === selectedTaskId);
                    if (updated) setSelectedTaskRecord(updated);
                }
            }
        });
        doGetRequest(TASK_API.STOCKX_RATE_STATUS, {}, {
            onSuccess: res => setStockxRateStatus(res.data || []),
        });
    }

    const rateMode = (mode: StockXRateStatus['mode']) => {
        const values = {
            BULK_ACTIVE: {label: 'Bulk正常', color: 'green'},
            SINGLE_FALLBACK: {label: 'Single降级', color: 'orange'},
            BULK_RECOVERING: {label: 'Bulk恢复中', color: 'blue'},
            GLOBAL_COOLDOWN: {label: '双通道冷却', color: 'red'},
        } as const;
        return values[mode] || {label: mode, color: 'default'};
    };

    const nextProbeText = (record: StockXRateStatus) => {
        const timestamp = record.mode === 'GLOBAL_COOLDOWN'
            ? record.nextGlobalProbeAt : record.nextBatchProbeAt;
        return timestamp > 0 ? moment(timestamp).format('MM-DD HH:mm:ss') : '-';
    };

    const rateColumns = [
        {title: '账号', dataIndex: 'accountName', width: 130},
        {title: '通道', dataIndex: 'mode', width: 120, render: (mode: StockXRateStatus['mode']) => {
            const value = rateMode(mode);
            return <Tag color={value.color}>{value.label}</Tag>;
        }},
        {title: '下次真实探测', width: 145, render: (_: any, record: StockXRateStatus) => nextProbeText(record)},
        {title: 'Bulk请求 / 商品', width: 135, render: (_: any, r: StockXRateStatus) => `${r.bulkRequestCount} / ${r.bulkItemCount}`},
        {title: 'Single请求 / 商品', width: 140, render: (_: any, r: StockXRateStatus) => `${r.singleRequestCount} / ${r.singleItemCount}`},
        {title: '批量429 / 通用429', width: 140, render: (_: any, r: StockXRateStatus) => `${r.batchRateLimitCount} / ${r.generalRateLimitCount}`},
        {title: '无响应', dataIndex: 'noResponseCount', width: 80},
        {title: '探测成功 / 次数', width: 125, render: (_: any, r: StockXRateStatus) => `${r.probeSuccessCount} / ${r.probeAttemptCount}`},
        {title: '已确认压价', dataIndex: 'confirmedPriceUpdateCount', width: 100},
        {title: '最近信号', dataIndex: 'lastSignal', width: 125, render: (v: string) => v || '-'},
    ];

    const handleCancelTask = (record: TaskRecord) => {
        doPostRequest(`${TASK_API.CANCEL_BY_ID}?taskId=${record.id}`, {}, {
            onSuccess: () => { message.success('已发送取消信号'); setTimeout(queryTaskList, 2000); }
        });
    }

    const handleDeleteTask = (record: TaskRecord) => {
        doDeleteRequest(TASK_API.DELETE, {taskId: record.id}, {
            onSuccess: () => { message.success("删除成功"); queryTaskList(); }
        });
    }

    const handleResumeTask = (record: TaskRecord) => {
        doPostRequest(`${TASK_API.LIFECYCLE}/${record.id}/resume`, {}, {
            onSuccess: () => {
                message.success('任务已继续执行');
                queryTaskList();
            },
            onError: res => message.error(res.errorMsg || '任务继续执行失败'),
        });
    }

    const handleRerunTask = (record: TaskRecord) => {
        doPostRequest(`${TASK_API.LIFECYCLE}/${record.id}/rerun`, {}, {
            onSuccess: res => {
                message.success(`已创建重跑任务${res.data ? ` #${res.data}` : ''}`);
                queryTaskList();
            },
            onError: res => message.error(res.errorMsg || '任务重跑失败'),
        });
    }

    // ==================== 新建任务 ====================

    const openCreateModal = () => {
        createForm.resetFields();
        setCreatePlatform('stockx');
        setCreateTaskType('price_down');
        setCreateModalVisible(true);
        if (stockxAccounts.length === 0) {
            doGetRequest(SETTING_API.STOCKX_ACCOUNTS, {}, {
                onSuccess: res => setStockxAccounts((res.data || []).filter((a: any) => a.enabled))
            });
        }
    }

    const handleCreateTask = () => {
        createForm.validateFields().then((values: any) => {
            setCreating(true);
            if (createPlatform === 'stockx' && createTaskType === 'listing') {
                doPostRequest(TASK_API.START_SEARCH_LIST, {
                    accountId: values.accountId,
                    keywords: values.keywords,
                    sorts: (values.sorts || ['lowest_ask']).join(','),
                    pageCount: values.pageCount || 3,
                    searchType: values.searchType || 'shoes',
                    maxListCount: values.maxListCount || 0,
                }, {
                    onSuccess: () => { message.success('任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                    onFinally: () => setCreating(false),
                });
            } else if (createPlatform === 'stockx' && createTaskType === 'model_search') {
                const file = values.modelNoExcel?.[0]?.originFileObj;
                if (!file) { message.error('请上传货号Excel'); setCreating(false); return; }
                doUploadRequestWithParams(TASK_API.START_MODEL_NO_SEARCH_LIST, file, {
                    accountId: values.accountId,
                    maxListCount: values.maxListCount || 0,
                }, {
                    onSuccess: () => { message.success('货号搜索上架任务已创建'); setCreateModalVisible(false); queryTaskList(); setCreating(false); },
                    onError: () => { message.error('任务创建失败'); setCreating(false); },
                });
            } else if (createPlatform === 'stockx' && createTaskType === 'price_down') {
                const accountId = values.accountId;
                const inventoryType = values.inventoryType || 'STANDARD';
                const file = values.excelFile?.[0]?.originFileObj;
                const startPriceDown = () => {
                    const hasExcel = !!file;
                    const listingFetchMode = hasExcel ? (values.listingFetchMode || 'all') : 'all';
                    doPostRequest(TASK_API.STOCKX_START_EXCEL_PRICE_DOWN, {
                        accountId, inventoryType, hasExcel,
                        interval: values.interval || 1800,
                        listingFetchMode,
                        processOutsideExcel: hasExcel
                            ? (listingFetchMode === 'all' && (values.processOutsideExcel || false))
                            : true,
                        unprofitableAction: values.unprofitableAction || 'markup',
                    }, {
                        onSuccess: () => { message.success('压价任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                        onFinally: () => setCreating(false),
                    });
                };
                if (file) {
                    doUploadRequestWithParams(TASK_API.STOCKX_UPLOAD_PRICE_DOWN_EXCEL, file, {accountId, inventoryType}, {
                        onSuccess: startPriceDown,
                        onError: () => { message.error('Excel上传失败'); setCreating(false); },
                    });
                } else {
                    startPriceDown();
                }
            } else if (createPlatform === 'stockx' && createTaskType === 'fetch_listings') {
                doPostRequest(TASK_API.START_FETCH_LISTINGS, {
                    accountId: values.accountId,
                    inventoryType: values.inventoryType || 'STANDARD',
                }, {
                    onSuccess: () => { message.success('任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                    onFinally: () => setCreating(false),
                });
            } else if (createPlatform === 'stockx' && createTaskType === 'excel_delist') {
                const accountId = values.accountId;
                const inventoryType = values.inventoryType || 'STANDARD';
                const file = values.delistExcelFile?.[0]?.originFileObj;
                if (!file) { message.error('请上传下架Excel文件'); setCreating(false); return; }
                doUploadRequestWithParams(TASK_API.UPLOAD_DELIST_EXCEL, file, {accountId, inventoryType}, {
                    onSuccess: (res: any) => {
                        message.success(`已加载${res.data}条下架规则`);
                        doPostRequest(TASK_API.START_EXCEL_DELIST, {accountId, inventoryType}, {
                            onSuccess: () => { message.success('Excel下架任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                            onFinally: () => setCreating(false),
                        });
                    },
                    onError: () => { message.error('Excel上传失败'); setCreating(false); },
                });
            } else if (createPlatform === 'stockx' && createTaskType === 'fetch_orders') {
                doPostRequest(TASK_API.START_FETCH_ORDERS, {
                    accountId: values.accountId,
                    orderTypes: values.orderTypes || [],
                }, {
                    onSuccess: () => { message.success('获取订单任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                    onFinally: () => setCreating(false),
                });
            } else if (createPlatform === 'stockx' && createTaskType === 'extend_shipping') {
                doPostRequest(TASK_API.START_SHIPPING_EXTENSION, {
                    accountId: values.accountId,
                }, {
                    onSuccess: () => { message.success('订单延期任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                    onFinally: () => setCreating(false),
                });
            } else {
                // KC
                doPostRequest(`${TASK_API.START}?taskType=${createTaskType}`, {}, {
                    onSuccess: () => { message.success('任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                    onFinally: () => setCreating(false),
                });
            }
        });
    }

    // ==================== 列定义 ====================

    const columns = [
        {
            title: '平台', dataIndex: 'platform', key: 'platform', width: 80,
            render: (platform: string) => ({ stockx: 'StockX', kickscrew: 'KC' }[platform] || platform),
        },
        {
            title: '任务类型', dataIndex: 'taskType', key: 'type', width: 120,
            render: (taskType: string) => TASK_TYPE_LABELS[taskType] || taskType,
        },
        {
            title: '账号', dataIndex: 'accountName', key: 'accountName', width: 90,
            render: (name: string) => name || '-',
        },
        {
            title: '开始时间', dataIndex: 'startTime', key: 'startTime', width: 160,
        },
        {
            title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 160,
        },
        {
            title: '耗时', dataIndex: 'cost', key: 'cost', width: 100,
        },
        {
            title: '状态', dataIndex: 'status', key: 'status', width: 90,
            render: (status: string, record: TaskRecord) => {
                const statusMap: Record<string, {text: string, color: string}> = {
                    'running': {text: '运行中', color: 'blue'},
                    '运行中': {text: '运行中', color: 'blue'},
                    'success': {text: '成功', color: 'green'},
                    '执行成功': {text: '成功', color: 'green'},
                    'failed': {text: '失败', color: 'red'},
                    '执行失败': {text: '失败', color: 'red'},
                    'paused': {text: '已暂停', color: 'orange'},
                    '已暂停': {text: '已暂停', color: 'orange'},
                    'cancel': {text: '已取消', color: 'gray'},
                    '已取消': {text: '已取消', color: 'gray'},
                    '已搁置': {text: '已搁置', color: 'orange'},
                };
                const info = statusMap[status] || {text: status, color: 'default'};
                const node = <span style={{color: info.color, fontWeight: 500}}>{info.text}</span>;
                return record.failReason ? <Tooltip title={record.failReason}>{node}</Tooltip> : node;
            }
        },
        {
            title: '成功数量', key: 'operationCounts', width: 190,
            render: (_: any, record: TaskRecord) => (
                <TaskOperationCounts
                    priceDownCount={record.priceDownCount}
                    listingCount={record.listingCount}
                    delistCount={record.delistCount}
                    pendingOperationCount={record.pendingOperationCount}
                />
            ),
        },
        {
            title: '进度', key: 'progress', width: 100,
            render: (_: any, record: TaskRecord) => {
                if (record.taskType === 'listing' && record.attributes) {
                    try {
                        const attrs = JSON.parse(record.attributes);
                        const tip = `${attrs.detail || ''} | 搜索进度 ${attrs.progress ?? 0}%`;
                        return <Tooltip title={tip}>
                            <span style={{cursor: 'pointer', lineHeight: 1.3, display: 'inline-block'}}>
                                已提交上架 {attrs.listed ?? 0}
                                {attrs.processed != null && <><br/>已处理 {attrs.processed}</>}
                                {attrs.keywordTotal != null && <><br/>词 {attrs.keywordIdx ?? 0}/{attrs.keywordTotal}</>}
                            </span>
                        </Tooltip>;
                    } catch { return '-'; }
                }
                if (record.taskType === 'extend_shipping' && record.attributes) {
                    try {
                        const attrs = JSON.parse(record.attributes);
                        const tip = `扫描 ${attrs.scanned ?? 0} | 已延期 ${attrs.alreadyExtended ?? 0} | 跳过 ${attrs.skipped ?? 0}`;
                        return <Tooltip title={tip}>
                            <span style={{cursor: 'pointer', lineHeight: 1.3, display: 'inline-block'}}>
                                成功 {attrs.extended ?? 0}<br/>失败 {attrs.failed ?? 0}
                            </span>
                        </Tooltip>;
                    } catch { return '-'; }
                }
                if (record.round == null) return '-';
                const unitMap: Record<string, string> = {
                    fetch_listings: '页',
                    excel_delist: '批',
                    fetch_orders: '页',
                };
                const unit = unitMap[record.taskType] || '轮';
                return `第${record.round}${unit}`;
            },
        },
        {
            title: '操作', key: 'action', width: 310,
            render: (_: any, record: TaskRecord) => (
                <Space size={0} wrap>
                    <Button type="link" size="small" onClick={() => { setSelectedTaskId(record.id); setSelectedTaskRecord(record); setTaskItemModalVisible(true); }}>
                        明细
                    </Button>
                    {record.params && (
                        <Button type="link" size="small" onClick={() => {
                            try { setParamsModalData(JSON.parse(record.params)); } catch { setParamsModalData({raw: record.params}); }
                            setParamsModalVisible(true);
                        }}>参数</Button>
                    )}
                    {record.taskType === 'price_down' && record.platform === 'stockx' && (
                        <Button type="link" size="small" onClick={() => {
                            try {
                                const p = JSON.parse(record.params || '{}');
                                doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_DATA, {accountId: record.accountName, inventoryType: p.inventoryType || 'STANDARD'}, {
                                    onSuccess: (res: any) => {
                                        setPreviewData(res.data || []);
                                        setPreviewTitle(`${record.accountName} ${(p.inventoryType === 'CUSTODIAL' ? '寄存' : '现货')} 压价Excel`);
                                        setPreviewColumns([
                                            {title: '货号', dataIndex: 'styleId', key: 'styleId'},
                                            {title: '尺码', dataIndex: 'size', key: 'size'},
                                            {title: '最低价($)', dataIndex: 'minPrice', key: 'minPrice', render: (v: number) => v === -1 ? '跳过' : `$${v}`},
                                        ]);
                                        setPreviewVisible(true);
                                    }
                                });
                            } catch {}
                        }}>Excel</Button>
                    )}
                    {record.taskType === 'excel_delist' && record.platform === 'stockx' && (
                        <Button type="link" size="small" onClick={() => {
                            try {
                                const p = JSON.parse(record.params || '{}');
                                doGetRequest(TASK_API.DELIST_EXCEL_DATA, {accountId: record.accountName, inventoryType: p.inventoryType || 'STANDARD'}, {
                                    onSuccess: (res: any) => {
                                        setPreviewData(res.data || []);
                                        setPreviewTitle(`${record.accountName} ${(p.inventoryType === 'CUSTODIAL' ? '寄存' : '现货')} 下架Excel`);
                                        setPreviewColumns([
                                            {title: 'listingId', dataIndex: 'listingId', key: 'listingId'},
                                            {title: '货号', dataIndex: 'styleId', key: 'styleId'},
                                            {title: '尺码', dataIndex: 'size', key: 'size'},
                                        ]);
                                        setPreviewVisible(true);
                                    }
                                });
                            } catch {}
                        }}>Excel</Button>
                    )}
                    {(record.status === 'running' || record.status === '运行中') && record.taskType !== 'extend_shipping' && (
                        <Popconfirm title="确认终止此任务？" onConfirm={() => handleCancelTask(record)} okText="确定" cancelText="取消">
                            <Button type="link" size="small" style={{color: '#faad14'}}>终止</Button>
                        </Popconfirm>
                    )}
                    {(record.status === 'paused' || record.status === '已暂停') && (
                        <Popconfirm
                            title="继续执行这个任务？"
                            description="将复用原任务ID和已完成进度；若仍被StockX限流，任务会再次暂停。"
                            onConfirm={() => handleResumeTask(record)} okText="继续执行" cancelText="取消"
                        >
                            <Button type="link" size="small">继续</Button>
                        </Popconfirm>
                    )}
                    {record.rerunnable && record.status !== 'running' && record.status !== '运行中' && (
                        <Popconfirm
                            title="确认重跑此任务？"
                            description="将复制原平台、账号和任务参数，创建一个新的任务记录。"
                            onConfirm={() => handleRerunTask(record)} okText="创建并执行" cancelText="取消"
                        >
                            <Button type="link" size="small" icon={<RedoOutlined/>}>重跑</Button>
                        </Popconfirm>
                    )}
                    {record.status !== 'running' && record.status !== '运行中' && (
                        <Popconfirm title="确认删除" description="将删除任务及所有明细数据" onConfirm={() => handleDeleteTask(record)} okText="确定" cancelText="取消">
                            <Button type="link" size="small" danger>删除</Button>
                        </Popconfirm>
                    )}
                </Space>
            ),
        }
    ];

    // ==================== 新建任务表单 ====================

    const renderCreateForm = () => {
        if (createPlatform === 'stockx' && createTaskType === 'listing') {
            return <>
                <Form.Item name="keywords" label="关键词" rules={[{required: true, message: '请输入关键词'}]}
                           extra="每行一个关键词">
                    <Input.TextArea rows={3} placeholder={"jordan retro\nyeezy slides"}/>
                </Form.Item>
                <Form.Item name="sorts" label="排序方式" initialValue={['featured']}>
                    <Select mode="multiple" placeholder="选择排序方式" options={SORT_OPTIONS}/>
                </Form.Item>
                <Form.Item name="pageCount" label="查询页数" initialValue={25}>
                    <InputNumber min={1} max={50} style={{width: 120}}/>
                </Form.Item>
                <Form.Item name="searchType" label="搜索类型" initialValue="shoes">
                    <Radio.Group>
                        <Radio.Button value="shoes">鞋类</Radio.Button>
                        <Radio.Button value="clothes">服饰</Radio.Button>
                    </Radio.Group>
                </Form.Item>
                <Form.Item name="maxListCount" label="最大上架数" extra="不填或填0表示不限制">
                    <InputNumber min={0} style={{width: 160}} placeholder="不限"/>
                </Form.Item>
            </>;
        }

        if (createPlatform === 'stockx' && createTaskType === 'model_search') {
            return <>
                <Form.Item name="modelNoExcel" label="货号Excel" valuePropName="fileList"
                           getValueFromEvent={(e: any) => e?.fileList} rules={[{required: true, message: '请上传货号Excel'}]}
                           extra="Excel需包含「货号」列">
                    <Upload accept=".xlsx,.xls" maxCount={1} beforeUpload={() => false}>
                        <Button icon={<UploadOutlined/>}>选择文件</Button>
                    </Upload>
                </Form.Item>
                <Form.Item name="maxListCount" label="最大上架数" extra="不填或填0表示不限制">
                    <InputNumber min={0} style={{width: 160}} placeholder="不限"/>
                </Form.Item>
            </>;
        }

        if (createPlatform === 'stockx' && createTaskType === 'price_down') {
            return <>
                <Form.Item name="inventoryType" label="库存类型" initialValue="STANDARD">
                    <Radio.Group>
                        <Radio.Button value="STANDARD">现货</Radio.Button>
                        <Radio.Button value="CUSTODIAL">寄存</Radio.Button>
                    </Radio.Group>
                </Form.Item>
                <Form.Item name="excelFile" label="压价Excel" valuePropName="fileList"
                           getValueFromEvent={(e: any) => e?.fileList}
                           extra="不上传则对所有在售商品按得物比价压价">
                    <Upload accept=".xlsx,.xls" maxCount={1} beforeUpload={() => false}>
                        <Button icon={<UploadOutlined/>}>选择文件</Button>
                    </Upload>
                </Form.Item>
                <Form.Item name="interval" label="轮询间隔" initialValue={1800}>
                    <InputNumber min={10} style={{width: 120}} addonAfter="秒"/>
                </Form.Item>
                <Form.Item noStyle shouldUpdate={(prev, cur) => prev.excelFile !== cur.excelFile}>
                    {({getFieldValue}) => getFieldValue('excelFile')?.length > 0 && (
                        <Form.Item name="listingFetchMode" label="商品获取方式" initialValue="all"
                                   extra="按Excel货号搜索会逐个查询Excel中的货号，适合账号在售商品很多、Excel商品较少的场景">
                            <Radio.Group>
                                <Radio.Button value="all">全量扫描</Radio.Button>
                                <Radio.Button value="excel_search">按Excel货号搜索</Radio.Button>
                            </Radio.Group>
                        </Form.Item>
                    )}
                </Form.Item>
                <Form.Item noStyle shouldUpdate={(prev, cur) => prev.excelFile !== cur.excelFile || prev.listingFetchMode !== cur.listingFetchMode}>
                    {({getFieldValue}) => getFieldValue('excelFile')?.length > 0 && (
                        getFieldValue('listingFetchMode') !== 'excel_search' && (
                            <Form.Item name="processOutsideExcel" label="Excel外商品" valuePropName="checked" initialValue={false}>
                                <Switch checkedChildren="处理" unCheckedChildren="跳过"/>
                            </Form.Item>
                        )
                    )}
                </Form.Item>
                <Form.Item noStyle shouldUpdate={(prev, cur) => prev.processOutsideExcel !== cur.processOutsideExcel || prev.excelFile !== cur.excelFile || prev.listingFetchMode !== cur.listingFetchMode}>
                    {({getFieldValue}) => ((getFieldValue('processOutsideExcel') && getFieldValue('listingFetchMode') !== 'excel_search')
                        || !(getFieldValue('excelFile')?.length > 0)) && (
                        <Form.Item name="unprofitableAction" label="不盈利操作" initialValue="markup">
                            <Radio.Group>
                                <Radio.Button value="markup">加价$100</Radio.Button>
                                <Radio.Button value="delist">下架</Radio.Button>
                            </Radio.Group>
                        </Form.Item>
                    )}
                </Form.Item>
            </>;
        }

        if (createPlatform === 'stockx' && createTaskType === 'fetch_listings') {
            return <>
                <Form.Item name="inventoryType" label="库存类型" initialValue="STANDARD">
                    <Radio.Group>
                        <Radio.Button value="STANDARD">现货</Radio.Button>
                        <Radio.Button value="CUSTODIAL">寄存</Radio.Button>
                    </Radio.Group>
                </Form.Item>
            </>;
        }

        if (createPlatform === 'stockx' && createTaskType === 'excel_delist') {
            return <>
                <Form.Item name="inventoryType" label="库存类型" initialValue="STANDARD">
                    <Radio.Group>
                        <Radio.Button value="STANDARD">现货</Radio.Button>
                        <Radio.Button value="CUSTODIAL">寄存</Radio.Button>
                    </Radio.Group>
                </Form.Item>
                <Form.Item name="delistExcelFile" label="下架Excel" valuePropName="fileList"
                           getValueFromEvent={(e: any) => e?.fileList} rules={[{required: true, message: '请上传Excel'}]}
                           extra="使用获取上架商品导出的Excel，需包含listingId列">
                    <Upload accept=".xlsx,.xls" maxCount={1} beforeUpload={() => false}>
                        <Button icon={<UploadOutlined/>}>选择文件</Button>
                    </Upload>
                </Form.Item>
            </>;
        }

        if (createPlatform === 'stockx' && createTaskType === 'fetch_orders') {
            return <>
                <Form.Item name="orderTypes" label="订单类型" initialValue={['pending']}
                           rules={[{required: true, message: '请至少选择一种订单类型'}]}
                           extra="可同时获取多种类型，结果会合并到同一个任务明细中">
                    <Select mode="multiple" options={STOCKX_ORDER_TYPE_OPTIONS} placeholder="选择订单类型"/>
                </Form.Item>
            </>;
        }

        return <div style={{color: '#999', padding: '8px 0', textAlign: 'center'}}>
            该任务类型无需额外配置，直接点击创建即可
        </div>;
    };

    // ==================== 参数 Modal ====================

    const PARAM_LABELS: Record<string, string> = {
        inventoryType: '库存类型', keywords: '关键词', sorts: '排序方式',
        pageCount: '查询页数', searchType: '搜索类型', interval: '执行间隔',
        maxListCount: '最大上架数', modelNoSearch: '货号搜索模式', listingFetchMode: '商品获取方式', processOutsideExcel: '处理Excel外商品', unprofitableAction: '不盈利操作',
        orderTypes: '订单类型',
        trigger: '触发方式', intervalHours: '自动间隔',
    };

    const formatParamValue = (k: string, v: any): string => {
        if (k === 'inventoryType') return v === 'STANDARD' ? '现货' : '寄存';
        if (k === 'processOutsideExcel') return v ? '是' : '否';
        if (k === 'listingFetchMode') return v === 'excel_search' ? '按Excel货号搜索' : '全量扫描';
        if (k === 'searchType') return v === 'shoes' ? '鞋类' : '服饰';
        if (k === 'unprofitableAction') return v === 'markup' ? '加价$100' : '下架';
        if (k === 'trigger') return v === 'scheduled' ? '自动触发' : '手动触发';
        if (k === 'intervalHours') return `${v}小时`;
        if (k === 'orderTypes') {
            const labels: Record<string, string> = {pending: '待处理', completed: '已完成', cancelled: '已取消', pending_payout: '待付款'};
            const values = Array.isArray(v) ? v : String(v).split(',');
            return values.map((value: string) => labels[value] || value).join(', ');
        }
        if (k === 'maxListCount') return v && v > 0 ? `${v}条` : '不限';
        if (k === 'interval') return `${v}秒`;
        if (k === 'sorts') return String(v).split(',').join(', ');
        if (k === 'keywords') return String(v).split('\n').join(', ');
        return String(v);
    };

    // ==================== 渲染 ====================

    return <>
        <Card title="StockX 压价通道观测" size="small" style={{marginBottom: 16}}>
            <Alert type="info" showIcon style={{marginBottom: 12}}
                   message="以下是本服务实际调用计数与真实429观测，不是StockX官方剩余额度，也不会按计数主动拦截请求。"/>
            <Table columns={rateColumns} dataSource={stockxRateStatus} rowKey="accountName"
                   size="small" pagination={false} scroll={{x: 1250}}/>
        </Card>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16}}>
            <Form form={conditionForm} layout="inline" style={{flex: 1, flexWrap: 'wrap', gap: 8}}>
                <Form.Item name="platform" label="平台">
                    <Select style={{width: 120}} placeholder="全部" allowClear
                        options={[{label: 'KickScrew', value: 'kickscrew'}, {label: 'StockX', value: 'stockx'}]}/>
                </Form.Item>
                <Form.Item name="taskType" label="类型">
                    <Select style={{width: 130}} placeholder="全部" allowClear
                        options={STOCKX_TASK_OPTIONS}/>
                </Form.Item>
                <Form.Item name="status" label="状态">
                    <Select style={{width: 110}} placeholder="全部" allowClear
                        options={[
                            {label: '运行中', value: 'running'}, {label: '成功', value: 'success'},
                            {label: '失败', value: 'failed'}, {label: '已取消', value: 'cancel'},
                            {label: '已暂停', value: 'paused'},
                        ]}/>
                </Form.Item>
                <Form.Item>
                    <Space>
                        <Button type="primary" onClick={queryTaskList}>查询</Button>
                        <Button onClick={() => { conditionForm.resetFields(); queryTaskList(); }}>重置</Button>
                    </Space>
                </Form.Item>
            </Form>
            <Button type="primary" icon={<PlusOutlined/>} onClick={openCreateModal}>
                新建任务
            </Button>
        </div>

        <Table
            columns={columns} dataSource={taskList} rowKey="id" size="middle"
            pagination={{
                current: pageIndex, pageSize, total, showSizeChanger: true, showTotal: t => `共 ${t} 条`,
                onChange: (c, s) => { setPageIndex(c); setPageSize(s); }
            }}
        />

        {/* 新建任务 Modal */}
        <Modal
            title="新建任务" open={createModalVisible} width={500}
            onCancel={() => setCreateModalVisible(false)}
            onOk={handleCreateTask} confirmLoading={creating}
            okText="创建任务" cancelText="取消"
        >
            <Form form={createForm} layout="horizontal" labelCol={{span: 5}} wrapperCol={{span: 18}}
                  style={{marginTop: 24}}>
                <Form.Item label="平台">
                    <Select value={createPlatform} onChange={(v) => { setCreatePlatform(v); createForm.resetFields(); }}>
                        <Select.Option value="stockx">StockX</Select.Option>
                        <Select.Option value="kickscrew">KickScrew</Select.Option>
                    </Select>
                </Form.Item>
                {createPlatform === 'stockx' && (
                    <Form.Item name="accountId" label="账号" rules={[{required: true, message: '请选择账号'}]}>
                        <Select placeholder="选择账号" options={stockxAccounts.map((a: any) => ({label: a.name, value: a.name}))}/>
                    </Form.Item>
                )}
                <Form.Item label="任务类型">
                    <Select value={createTaskType} options={STOCKX_TASK_OPTIONS}
                            onChange={(v) => { setCreateTaskType(v); const acc = createForm.getFieldValue('accountId'); createForm.resetFields(); if (acc) createForm.setFieldValue('accountId', acc); }}/>
                </Form.Item>
                <Divider style={{margin: '8px 0 20px'}}/>
                {renderCreateForm()}
            </Form>
        </Modal>

        {/* 参数查看 Modal */}
        <Modal title="任务参数" open={paramsModalVisible} onCancel={() => setParamsModalVisible(false)} footer={null} width={480}>
            {paramsModalData && (
                <table style={{width: '100%', borderCollapse: 'collapse'}}>
                    <tbody>
                    {Object.entries(paramsModalData).filter(([key]) => key !== 'fetchPayout').map(([key, value]) => (
                        <tr key={key} style={{borderBottom: '1px solid #f0f0f0'}}>
                            <td style={{padding: '10px 12px', color: '#666', width: 140, fontWeight: 500}}>
                                {PARAM_LABELS[key] || key}
                            </td>
                            <td style={{padding: '10px 12px'}}>{formatParamValue(key, value)}</td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            )}
        </Modal>

        {/* Excel预览 Modal */}
        <Modal title={previewTitle} open={previewVisible} onCancel={() => setPreviewVisible(false)} footer={null} width={600}>
            <Table dataSource={previewData} rowKey={(r, i) => `${r.listingId || r.styleId}:${r.size}:${i}`} size="small"
                pagination={{pageSize: 20, showTotal: (t: number) => `共 ${t} 条`}}
                columns={previewColumns}
            />
        </Modal>

        {/* 任务明细 Modal */}
        <TaskItemModal
            visible={taskItemModalVisible} taskId={selectedTaskId}
            onClose={() => { setTaskItemModalVisible(false); setSelectedTaskId(null); setSelectedTaskRecord(null); }}
            taskType={selectedTaskRecord?.taskType}
            attributes={selectedTaskRecord?.attributes}
            round={selectedTaskRecord?.round}
            defaultAutoRefresh={selectedTaskRecord?.status === 'running' || selectedTaskRecord?.status === '运行中'}
        />
    </>
}

export default TaskPage;
