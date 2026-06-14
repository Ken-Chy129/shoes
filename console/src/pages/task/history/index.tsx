import {
    Button, DatePicker, Form, Input, InputNumber, message, Modal, Popconfirm,
    Radio, Select, Space, Table, Tooltip, Upload, Switch, Tag, Badge, Divider,
} from "antd";
import {PlusOutlined, UploadOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest, doUploadRequestWithParams} from "@/util/http";
import {TASK_API, TASK_TYPE} from "@/services/task";
import {SETTING_API} from "@/services/shoes";
import moment from "moment";
import TaskItemModal from "../components/TaskItemModal";

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

const TYPE_LABELS: Record<string, string> = { listing: '上架', price_down: '压价', fetch_listings: '获取商品', excel_delist: 'Excel下架' };
const PLATFORM_LABELS: Record<string, string> = { stockx: 'StockX', kickscrew: 'KC' };

const TaskPage = () => {
    const [conditionForm] = Form.useForm();
    const [taskList, setTaskList] = useState<TaskRecord[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    const [taskItemModalVisible, setTaskItemModalVisible] = useState(false);
    const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);
    const [selectedTaskRecord, setSelectedTaskRecord] = useState<TaskRecord | null>(null);

    const [paramsModalVisible, setParamsModalVisible] = useState(false);
    const [paramsModalData, setParamsModalData] = useState<Record<string, any> | null>(null);

    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewData, setPreviewData] = useState<any[]>([]);
    const [previewTitle, setPreviewTitle] = useState('');

    // 新建任务
    const [createModalVisible, setCreateModalVisible] = useState(false);
    const [createForm] = Form.useForm();
    const [createPlatform, setCreatePlatform] = useState<string>('stockx');
    const [createTaskType, setCreateTaskType] = useState<string>('listing');
    const [stockxAccounts, setStockxAccounts] = useState<any[]>([]);
    const [creating, setCreating] = useState(false);

    useEffect(() => { queryTaskList(); }, [pageIndex, pageSize]);

    useEffect(() => {
        const hasRunning = taskList.some(t => t.status === 'running' || t.status === '运行中');
        if (!hasRunning) return;
        const timer = setInterval(queryTaskList, 30000);
        return () => clearInterval(timer);
    }, [taskList]);

    useEffect(() => {
        const hasRunning = taskList.some(t => t.status === 'running' || t.status === '运行中');
        if (!hasRunning) return;
        const timer = setInterval(queryTaskList, 5000);
        return () => clearInterval(timer);
    }, [taskList]);

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
    }

    const handleCancelTask = (record: TaskRecord) => {
        if (record.platform === 'stockx' && record.taskType === 'price_down') {
            try {
                const p = JSON.parse(record.params || '{}');
                doPostRequest(TASK_API.STOCKX_CANCEL_EXCEL_PRICE_DOWN, {accountId: record.accountName, inventoryType: p.inventoryType || 'STANDARD'}, {
                    onSuccess: () => { message.success('已发送取消信号'); setTimeout(queryTaskList, 2000); }
                });
            } catch { message.error('无法解析任务参数'); }
        } else if (record.platform === 'stockx' && record.taskType === 'listing') {
            doPostRequest(TASK_API.CANCEL_SEARCH_LIST, {accountId: record.accountName}, {
                onSuccess: () => { message.success('已发送取消信号'); setTimeout(queryTaskList, 2000); }
            });
        } else if (record.platform === 'stockx' && record.taskType === 'fetch_listings') {
            try {
                const p = JSON.parse(record.params || '{}');
                doPostRequest(TASK_API.CANCEL_FETCH_LISTINGS, {accountId: record.accountName, inventoryType: p.inventoryType || 'STANDARD'}, {
                    onSuccess: () => { message.success('已发送取消信号'); setTimeout(queryTaskList, 2000); }
                });
            } catch { message.error('无法解析任务参数'); }
        } else if (record.platform === 'stockx' && record.taskType === 'excel_delist') {
            try {
                const p = JSON.parse(record.params || '{}');
                doPostRequest(TASK_API.CANCEL_EXCEL_DELIST, {accountId: record.accountName, inventoryType: p.inventoryType || 'STANDARD'}, {
                    onSuccess: () => { message.success('已发送取消信号'); setTimeout(queryTaskList, 2000); }
                });
            } catch { message.error('无法解析任务参数'); }
        } else {
            doPostRequest(`${TASK_API.CANCEL}?taskType=${record.taskType}`, {}, {
                onSuccess: () => { message.success('已终止'); setTimeout(queryTaskList, 2000); }
            });
        }
    }

    const handleDeleteTask = (record: TaskRecord) => {
        doDeleteRequest(TASK_API.DELETE, {taskId: record.id}, {
            onSuccess: () => { message.success("删除成功"); queryTaskList(); }
        });
    }

    // ==================== 新建任务 ====================

    const openCreateModal = () => {
        createForm.resetFields();
        setCreatePlatform('stockx');
        setCreateTaskType('listing');
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
            } else if (createPlatform === 'stockx' && createTaskType === 'price_down') {
                const accountId = values.accountId;
                const inventoryType = values.inventoryType || 'STANDARD';
                const file = values.excelFile?.[0]?.originFileObj;
                if (!file) { message.error('请上传Excel文件'); setCreating(false); return; }
                doUploadRequestWithParams(TASK_API.STOCKX_UPLOAD_PRICE_DOWN_EXCEL, file, {accountId, inventoryType}, {
                    onSuccess: () => {
                        doPostRequest(TASK_API.STOCKX_START_EXCEL_PRICE_DOWN, {
                            accountId, inventoryType,
                            processOutsideExcel: values.processOutsideExcel || false,
                            unprofitableAction: values.unprofitableAction || 'markup',
                        }, {
                            onSuccess: () => { message.success('压价任务已创建'); setCreateModalVisible(false); queryTaskList(); },
                            onFinally: () => setCreating(false),
                        });
                    },
                    onError: () => { message.error('Excel上传失败'); setCreating(false); },
                });
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
        },
        {
            title: '任务类型', dataIndex: 'taskType', key: 'type', width: 120,
            render: (taskType: string, record: TaskRecord) => {
                const label = TYPE_LABELS[taskType];
                if (label) return `${PLATFORM_LABELS[record.platform] || record.platform} ${label}`;
                return taskType;
            },
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
            title: '进度', key: 'progress', width: 100,
            render: (_: any, record: TaskRecord) => {
                if (record.taskType === 'listing' && record.attributes) {
                    try {
                        const attrs = JSON.parse(record.attributes);
                        const pct = attrs.progress ?? 0;
                        const tip = `${attrs.detail || ''}${attrs.listed != null ? ` | 已上架: ${attrs.listed}` : ''}`;
                        return <Tooltip title={tip}><span style={{cursor: 'pointer'}}>{pct}%</span></Tooltip>;
                    } catch { return '-'; }
                }
                return record.round != null ? `第${record.round}轮` : '-';
            },
        },
        {
            title: '操作', key: 'action', width: 200,
            render: (_: any, record: TaskRecord) => (
                <Space size={0}>
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
                                        setParamsModalData(null);
                                        setPreviewData(res.data || []);
                                        setPreviewTitle(`${record.accountName} ${(p.inventoryType === 'CUSTODIAL' ? '寄存' : '现货')} Excel`);
                                        setPreviewVisible(true);
                                    }
                                });
                            } catch {}
                        }}>Excel</Button>
                    )}
                    {(record.status === 'running' || record.status === '运行中') && (
                        <Popconfirm title="确认终止此任务？" onConfirm={() => handleCancelTask(record)} okText="确定" cancelText="取消">
                            <Button type="link" size="small" style={{color: '#faad14'}}>终止</Button>
                        </Popconfirm>
                    )}
                    <Popconfirm title="确认删除" description="将删除任务及所有明细数据" onConfirm={() => handleDeleteTask(record)} okText="确定" cancelText="取消">
                        <Button type="link" size="small" danger>删除</Button>
                    </Popconfirm>
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

        if (createPlatform === 'stockx' && createTaskType === 'price_down') {
            return <>
                <Form.Item name="inventoryType" label="库存类型" initialValue="STANDARD">
                    <Radio.Group>
                        <Radio.Button value="STANDARD">现货</Radio.Button>
                        <Radio.Button value="CUSTODIAL">寄存</Radio.Button>
                    </Radio.Group>
                </Form.Item>
                <Form.Item name="excelFile" label="压价Excel" valuePropName="fileList"
                           getValueFromEvent={(e: any) => e?.fileList} rules={[{required: true, message: '请上传Excel'}]}>
                    <Upload accept=".xlsx,.xls" maxCount={1} beforeUpload={() => false}>
                        <Button icon={<UploadOutlined/>}>选择文件</Button>
                    </Upload>
                </Form.Item>
                <Form.Item name="interval" label="轮询间隔" initialValue={1800}>
                    <InputNumber min={10} style={{width: 120}} addonAfter="秒"/>
                </Form.Item>
                <Form.Item name="processOutsideExcel" label="Excel外商品" valuePropName="checked" initialValue={false}>
                    <Switch checkedChildren="处理" unCheckedChildren="跳过"/>
                </Form.Item>
                <Form.Item noStyle shouldUpdate={(prev, cur) => prev.processOutsideExcel !== cur.processOutsideExcel}>
                    {({getFieldValue}) => getFieldValue('processOutsideExcel') && (
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

        return <div style={{color: '#999', padding: '8px 0', textAlign: 'center'}}>
            该任务类型无需额外配置，直接点击创建即可
        </div>;
    };

    // ==================== 参数 Modal ====================

    const PARAM_LABELS: Record<string, string> = {
        inventoryType: '库存类型', keywords: '关键词', sorts: '排序方式',
        pageCount: '查询页数', searchType: '搜索类型', interval: '执行间隔',
        maxListCount: '最大上架数', processOutsideExcel: '处理Excel外商品', unprofitableAction: '不盈利操作',
    };

    const formatParamValue = (k: string, v: any): string => {
        if (k === 'inventoryType') return v === 'STANDARD' ? '现货' : '寄存';
        if (k === 'processOutsideExcel') return v ? '是' : '否';
        if (k === 'searchType') return v === 'shoes' ? '鞋类' : '服饰';
        if (k === 'unprofitableAction') return v === 'markup' ? '加价$100' : '下架';
        if (k === 'maxListCount') return v && v > 0 ? `${v}条` : '不限';
        if (k === 'interval') return `${v}秒`;
        if (k === 'sorts') return String(v).split(',').join(', ');
        if (k === 'keywords') return String(v).split('\n').join(', ');
        return String(v);
    };

    // ==================== 渲染 ====================

    return <>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16}}>
            <Form form={conditionForm} layout="inline" style={{flex: 1, flexWrap: 'wrap', gap: 8}}>
                <Form.Item name="platform" label="平台">
                    <Select style={{width: 120}} placeholder="全部" allowClear
                        options={[{label: 'KickScrew', value: 'kickscrew'}, {label: 'StockX', value: 'stockx'}]}/>
                </Form.Item>
                <Form.Item name="taskType" label="类型">
                    <Select style={{width: 130}} placeholder="全部" allowClear
                        options={[
                            {label: '上架', value: 'listing'}, {label: '压价', value: 'price_down'},
                            {label: '获取商品', value: 'fetch_listings'}, {label: 'Excel下架', value: 'excel_delist'},
                        ]}/>
                </Form.Item>
                <Form.Item name="status" label="状态">
                    <Select style={{width: 110}} placeholder="全部" allowClear
                        options={[
                            {label: '运行中', value: 'running'}, {label: '成功', value: 'success'},
                            {label: '失败', value: 'failed'}, {label: '已取消', value: 'cancel'},
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
                    <Select value={createTaskType} onChange={(v) => { setCreateTaskType(v); const acc = createForm.getFieldValue('accountId'); createForm.resetFields(); if (acc) createForm.setFieldValue('accountId', acc); }}>
                        <Select.Option value="listing">搜索上架</Select.Option>
                        <Select.Option value="price_down">压价</Select.Option>
                        <Select.Option value="fetch_listings">获取上架商品</Select.Option>
                        <Select.Option value="excel_delist">Excel下架</Select.Option>
                    </Select>
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
                    {Object.entries(paramsModalData).map(([key, value]) => (
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
            <Table dataSource={previewData} rowKey={(r) => `${r.styleId}:${r.size}`} size="small"
                pagination={{pageSize: 20, showTotal: (t: number) => `共 ${t} 条`}}
                columns={[
                    {title: '货号', dataIndex: 'styleId', key: 'styleId'},
                    {title: '尺码', dataIndex: 'size', key: 'size'},
                    {title: '最低价($)', dataIndex: 'minPrice', key: 'minPrice', render: (v: number) => v === -1 ? '跳过' : `$${v}`},
                ]}
            />
        </Modal>

        {/* 任务明细 Modal */}
        <TaskItemModal
            visible={taskItemModalVisible} taskId={selectedTaskId}
            onClose={() => { setTaskItemModalVisible(false); setSelectedTaskId(null); setSelectedTaskRecord(null); }}
            taskType={selectedTaskRecord?.taskType}
            attributes={selectedTaskRecord?.attributes}
            round={selectedTaskRecord?.round}
        />
    </>
}

export default TaskPage;
