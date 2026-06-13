import {
    Button, Card,
    Form,
    Input,
    InputNumber,
    message,
    Radio,
    Select,
    Space,
    Switch,
    Tabs,
    Upload,
    Tag,
    Table,
    Modal,
    Divider,
    Badge,
} from "antd";
import {UploadOutlined, EyeOutlined, SearchOutlined, DollarOutlined, CloudUploadOutlined, FileSearchOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest, doUploadRequestWithParams} from "@/util/http";
import {TASK_API, TASK_TYPE} from "@/services/task";
import {SETTING_API} from "@/services/shoes";
import TaskItemModal from "@/pages/task/components/TaskItemModal";

const sectionStyle: React.CSSProperties = {
    background: '#fafafa',
    borderRadius: 8,
    padding: '20px 24px',
    marginBottom: 16,
};

const sectionTitleStyle: React.CSSProperties = {
    fontSize: 15,
    fontWeight: 600,
    marginBottom: 16,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
};

const TaskExecutorPage = () => {
    const [taskForm] = Form.useForm();
    const [kcTaskStatus, setKcTaskStatus] = useState<boolean>(false);
    const [kcPriceDownTaskStatus, setKcPriceDownTaskStatus] = useState<boolean>(false);

    const [stockxAccounts, setStockxAccounts] = useState<any[]>([]);
    const [selectedAccount, setSelectedAccount] = useState<string | undefined>(undefined);
    const [excelTaskStates, setExcelTaskStates] = useState<Record<string, any>>({});

    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewData, setPreviewData] = useState<any[]>([]);
    const [previewTitle, setPreviewTitle] = useState('');

    const [kcCurrentTaskId, setKcCurrentTaskId] = useState<string | null>(null);
    const [kcPriceDownCurrentTaskId, setKcPriceDownCurrentTaskId] = useState<string | null>(null);
    const [taskItemModalVisible, setTaskItemModalVisible] = useState(false);
    const [currentViewTaskId, setCurrentViewTaskId] = useState<string | null>(null);

    const [searchListStates, setSearchListStates] = useState<Record<string, any>>({});
    const [searchListForm] = Form.useForm();

    useEffect(() => {
        queryAllTaskStatus();
        queryAllTaskInterval();
        loadStockXAccounts();
    }, []);

    // ==================== 查询方法 ====================

    const queryAllTaskStatus = () => {
        queryTaskStatus(TASK_TYPE.LISTING, setKcTaskStatus, setKcCurrentTaskId);
        queryTaskStatus(TASK_TYPE.PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId);
    }

    const queryAllTaskInterval = () => {
        queryTaskInterval(TASK_TYPE.PRICE_DOWN, "kcPriceDownTaskInterval");
    }

    const loadStockXAccounts = () => {
        doGetRequest(SETTING_API.STOCKX_ACCOUNTS, {}, {
            onSuccess: res => {
                const accounts = (res.data || []).filter((a: any) => a.enabled);
                setStockxAccounts(accounts);
                if (accounts.length > 0) {
                    const first = accounts[0].name;
                    setSelectedAccount(first);
                    refreshAccountState(first);
                }
                accounts.forEach((account: any) => {
                    ['STANDARD', 'CUSTODIAL'].forEach(inventoryType => {
                        refreshExcelTaskState(account.name, inventoryType);
                    });
                });
            }
        });
    }

    const refreshAccountState = (accountId: string) => {
        ['STANDARD', 'CUSTODIAL'].forEach(inventoryType => refreshExcelTaskState(accountId, inventoryType));
        loadSearchListStatus(accountId);
    }

    const refreshExcelTaskState = (accountId: string, inventoryType: string) => {
        const key = `${accountId}:${inventoryType}`;
        doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_COUNT, {accountId, inventoryType}, {
            onSuccess: res => {
                setExcelTaskStates(prev => ({...prev, [key]: {...(prev[key] || {}), excelCount: res.data || 0}}));
            }
        });
        doGetRequest(TASK_API.STOCKX_EXCEL_PRICE_DOWN_STATUS, {accountId, inventoryType}, {
            onSuccess: res => {
                setExcelTaskStates(prev => ({
                    ...prev,
                    [key]: {
                        ...(prev[key] || {}),
                        running: res.data?.running || false,
                        taskId: res.data?.taskId ? String(res.data.taskId) : null,
                        interval: res.data?.interval || 1800,
                    }
                }));
            }
        });
    }

    const queryTaskStatus = (taskType: string, setStatus: (s: boolean) => void, setTaskId?: (id: string | null) => void) => {
        doGetRequest(TASK_API.STATUS, {taskType}, {
            onSuccess: res => {
                setStatus(res.data);
                if (res.data && setTaskId) queryCurrentTaskId(taskType, setTaskId);
                else if (setTaskId) setTaskId(null);
            }
        });
    }

    const queryCurrentTaskId = (taskType: string, setTaskId: (id: string | null) => void) => {
        doGetRequest(TASK_API.CURRENT_TASK_ID, {taskType}, { onSuccess: res => setTaskId(res.data) });
    }

    const queryTaskInterval = (taskType: string, fieldName: string) => {
        doGetRequest(TASK_API.INTERVAL, {taskType}, { onSuccess: res => taskForm.setFieldValue(fieldName, res.data / 1000) });
    }

    const startTask = (taskType: string, onFinally: () => void) => {
        doPostRequest(`${TASK_API.START}?taskType=${taskType}`, {}, { onSuccess: _ => message.success("开始执行任务"), onFinally });
    }

    const cancelTask = (taskType: string, onFinally: () => void) => {
        doPostRequest(`${TASK_API.CANCEL}?taskType=${taskType}`, {}, { onSuccess: _ => message.success("已终止"), onFinally });
    }

    const updateTaskInterval = (taskType: string, fieldName: string) => {
        const interval = taskForm.getFieldValue(fieldName) * 1000;
        doPostRequest(`${TASK_API.INTERVAL}?taskType=${taskType}&interval=${interval}`, {}, { onSuccess: _ => message.success("配置已更新") });
    }

    // ==================== KC ====================

    const handleKcStart = () => startTask(TASK_TYPE.LISTING, () => queryTaskStatus(TASK_TYPE.LISTING, setKcTaskStatus, setKcCurrentTaskId));
    const handleKcCancel = () => cancelTask(TASK_TYPE.LISTING, () => queryTaskStatus(TASK_TYPE.LISTING, setKcTaskStatus, setKcCurrentTaskId));
    const handleKcPriceDownStart = () => startTask(TASK_TYPE.PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId));
    const handleKcPriceDownCancel = () => cancelTask(TASK_TYPE.PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId));

    // ==================== StockX 压价 ====================

    const handleUploadExcel = (file: any, accountId: string, inventoryType: string) => {
        doUploadRequestWithParams(TASK_API.STOCKX_UPLOAD_PRICE_DOWN_EXCEL, file, {accountId, inventoryType}, {
            onSuccess: res => { message.success(`已加载 ${res.data} 条数据`); refreshExcelTaskState(accountId, inventoryType); },
            onError: () => message.error('上传失败'),
        });
        return false;
    }

    const handleExcelPriceDownStart = (accountId: string, inventoryType: string) => {
        const state = getExcelState(accountId, inventoryType);
        doPostRequest(TASK_API.STOCKX_START_EXCEL_PRICE_DOWN, {
            accountId, inventoryType,
            processOutsideExcel: state.processOutside || false,
            unprofitableAction: state.unprofitableAction || 'markup',
        }, { onSuccess: () => { message.success('任务已启动'); refreshExcelTaskState(accountId, inventoryType); } });
    }

    const handleExcelPriceDownCancel = (accountId: string, inventoryType: string) => {
        doPostRequest(TASK_API.STOCKX_CANCEL_EXCEL_PRICE_DOWN, {accountId, inventoryType}, {
            onSuccess: () => { message.success('已发送取消信号'); setTimeout(() => refreshExcelTaskState(accountId, inventoryType), 2000); }
        });
    }

    const handleSetExcelInterval = (accountId: string, inventoryType: string, interval: number) => {
        doPostRequest(TASK_API.STOCKX_SET_EXCEL_PRICE_DOWN_INTERVAL, {accountId, inventoryType, interval}, { onSuccess: () => message.success('间隔已保存') });
    }

    const getExcelState = (accountId: string, inventoryType: string) => excelTaskStates[`${accountId}:${inventoryType}`] || {};

    // ==================== StockX 搜索上架 ====================

    const sortOptions = [
        {label: '精选', value: 'featured'},
        {label: 'Top Selling', value: 'most-active'},
        {label: 'Price: Low to High', value: 'lowest_ask'},
        {label: '出价: 从高到低', value: 'highest_bid'},
        {label: 'Recent Price Drops', value: 'recent_asks'},
        {label: 'Total Sold: High to Low', value: 'deadstock_sold'},
        {label: '发布日期', value: 'release_date'},
        {label: 'Last Sale: High to Low', value: 'last_sale'},
    ];

    const loadSearchListStatus = (accountId: string) => {
        doGetRequest(TASK_API.SEARCH_LIST_STATUS, {accountId}, {
            onSuccess: (res: any) => { setSearchListStates(prev => ({...prev, [accountId]: res.data})); },
        });
    };

    const handleStartSearchList = () => {
        if (!selectedAccount) { message.warning('请先选择账号'); return; }
        searchListForm.validateFields().then((values: any) => {
            const {keywords, sorts, pageCount, searchType} = values;
            doPostRequest(TASK_API.START_SEARCH_LIST, {
                accountId: selectedAccount, keywords,
                sorts: (sorts || ['lowest_ask']).join(','),
                pageCount: pageCount || 3,
                searchType: searchType || 'shoes',
            }, { onSuccess: () => { message.success('搜索上架任务已启动'); loadSearchListStatus(selectedAccount!); } });
        });
    };

    const handleCancelSearchList = () => {
        if (!selectedAccount) return;
        doPostRequest(TASK_API.CANCEL_SEARCH_LIST, {accountId: selectedAccount}, {
            onSuccess: () => { message.info('已发送取消请求'); setTimeout(() => loadSearchListStatus(selectedAccount!), 2000); },
        });
    };

    const searchListState = selectedAccount ? (searchListStates[selectedAccount] || {}) : {};

    const openTaskDetail = (taskId: string | null) => {
        if (taskId) { setCurrentViewTaskId(taskId); setTaskItemModalVisible(true); }
    };

    // ==================== 渲染：压价面板 ====================

    const renderPriceDownPanel = (inventoryType: string) => {
        if (!selectedAccount) return null;
        const state = getExcelState(selectedAccount, inventoryType);
        const {excelCount = 0, running = false, taskId = null, interval = 1800} = state;

        return (
            <div style={{padding: '16px 0'}}>
                <div style={{display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20, flexWrap: 'wrap'}}>
                    <Upload accept=".xlsx,.xls" beforeUpload={(file) => handleUploadExcel(file, selectedAccount, inventoryType)} showUploadList={false}>
                        <Button icon={<UploadOutlined/>}>上传Excel</Button>
                    </Upload>
                    {excelCount > 0 && (
                        <Button icon={<EyeOutlined/>} onClick={() => {
                            doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_DATA, {accountId: selectedAccount, inventoryType}, {
                                onSuccess: res => { setPreviewData(res.data || []); setPreviewTitle(`${inventoryType === 'STANDARD' ? '现货' : '寄存'} Excel`); setPreviewVisible(true); }
                            });
                        }}>预览数据</Button>
                    )}
                    {excelCount > 0 && <Tag color="blue">已加载 {excelCount} 条</Tag>}
                    <Divider type="vertical" style={{height: 24}}/>
                    <span style={{color: '#666', fontSize: 13}}>轮询间隔</span>
                    <InputNumber
                        min={10} style={{width: 100}} size="small"
                        value={interval} addonAfter="s"
                        onChange={(val) => {
                            if (!val) return;
                            const key = `${selectedAccount}:${inventoryType}`;
                            setExcelTaskStates(prev => ({...prev, [key]: {...(prev[key] || {}), interval: val}}));
                            handleSetExcelInterval(selectedAccount, inventoryType, val);
                        }}
                    />
                </div>

                <div style={{display: 'flex', alignItems: 'center', gap: 16, marginBottom: 20, flexWrap: 'wrap'}}>
                    <span style={{color: '#666', fontSize: 13}}>处理Excel外商品</span>
                    <Switch size="small" checked={state.processOutside || false}
                        onChange={(checked) => {
                            const k = `${selectedAccount}:${inventoryType}`;
                            setExcelTaskStates(prev => ({...prev, [k]: {...(prev[k] || {}), processOutside: checked}}));
                        }}
                    />
                    {state.processOutside && (
                        <Radio.Group size="small" value={state.unprofitableAction || 'markup'}
                            onChange={(e) => {
                                const k = `${selectedAccount}:${inventoryType}`;
                                setExcelTaskStates(prev => ({...prev, [k]: {...(prev[k] || {}), unprofitableAction: e.target.value}}));
                            }}
                        >
                            <Radio.Button value="markup">不盈利加价$100</Radio.Button>
                            <Radio.Button value="delist">不盈利下架</Radio.Button>
                        </Radio.Group>
                    )}
                </div>

                <Space size="middle">
                    <Button type="primary" onClick={() => handleExcelPriceDownStart(selectedAccount, inventoryType)}
                            disabled={running || excelCount === 0}>
                        {running ? '运行中...' : '开启压价'}
                    </Button>
                    <Button danger onClick={() => handleExcelPriceDownCancel(selectedAccount, inventoryType)} disabled={!running}>
                        终止
                    </Button>
                    {taskId && <Button type="link" onClick={() => openTaskDetail(taskId)}>查看明细</Button>}
                    {running && <Badge status="processing" text="任务运行中"/>}
                </Space>
            </div>
        );
    };

    // ==================== 渲染 ====================

    const kcTab = (
        <div style={{padding: '8px 0'}}>
            <div style={sectionStyle}>
                <div style={sectionTitleStyle}><CloudUploadOutlined /> 上架</div>
                <Space size="middle">
                    <Button type="primary" onClick={handleKcStart} disabled={kcTaskStatus}>
                        {kcTaskStatus ? '运行中...' : '开启上架'}
                    </Button>
                    <Button danger onClick={handleKcCancel} disabled={!kcTaskStatus}>终止</Button>
                    {kcTaskStatus && kcCurrentTaskId && <Button type="link" onClick={() => openTaskDetail(kcCurrentTaskId)}>查看明细</Button>}
                    {kcTaskStatus && <Badge status="processing" text="任务运行中"/>}
                </Space>
            </div>

            <div style={sectionStyle}>
                <div style={sectionTitleStyle}><DollarOutlined /> 压价</div>
                <Form form={taskForm}>
                    <Space align="center" style={{marginBottom: 16}}>
                        <Form.Item label="轮询间隔(秒)" name="kcPriceDownTaskInterval" style={{marginBottom: 0}}>
                            <InputNumber min={1} style={{width: 100}} size="small"/>
                        </Form.Item>
                        <Button size="small" onClick={() => updateTaskInterval(TASK_TYPE.PRICE_DOWN, "kcPriceDownTaskInterval")}>保存</Button>
                    </Space>
                </Form>
                <Space size="middle">
                    <Button type="primary" onClick={handleKcPriceDownStart} disabled={kcPriceDownTaskStatus}>
                        {kcPriceDownTaskStatus ? '运行中...' : '开启压价'}
                    </Button>
                    <Button danger onClick={handleKcPriceDownCancel} disabled={!kcPriceDownTaskStatus}>终止</Button>
                    {kcPriceDownTaskStatus && kcPriceDownCurrentTaskId && <Button type="link" onClick={() => openTaskDetail(kcPriceDownCurrentTaskId)}>查看明细</Button>}
                    {kcPriceDownTaskStatus && <Badge status="processing" text="任务运行中"/>}
                </Space>
            </div>
        </div>
    );

    const stockxTab = (
        <div style={{padding: '8px 0'}}>
            <div style={{marginBottom: 20, display: 'flex', alignItems: 'center', gap: 12}}>
                <span style={{fontWeight: 500, fontSize: 14}}>当前账号</span>
                <Select
                    style={{width: 200}}
                    placeholder="选择账号"
                    value={selectedAccount}
                    onChange={(v) => { setSelectedAccount(v); refreshAccountState(v); }}
                    options={stockxAccounts.map((a: any) => ({label: a.name, value: a.name}))}
                />
                {stockxAccounts.length === 0 && <span style={{color: '#999', fontSize: 13}}>暂无已启用的账号，请在设置中添加</span>}
            </div>

            {selectedAccount && (
                <Tabs
                    defaultActiveKey="priceDown"
                    items={[
                        {
                            key: 'priceDown',
                            label: <span><DollarOutlined style={{marginRight: 4}}/>压价</span>,
                            children: (
                                <div style={sectionStyle}>
                                    <Tabs
                                        type="card" size="small"
                                        items={[
                                            {key: 'STANDARD', label: '现货 (STANDARD)', children: renderPriceDownPanel('STANDARD')},
                                            {key: 'CUSTODIAL', label: '寄存 (CUSTODIAL)', children: renderPriceDownPanel('CUSTODIAL')},
                                        ]}
                                    />
                                </div>
                            ),
                        },
                        {
                            key: 'searchList',
                            label: <span><SearchOutlined style={{marginRight: 4}}/>搜索上架</span>,
                            children: (
                                <div style={sectionStyle}>
                                    <Form form={searchListForm} layout="vertical" style={{maxWidth: 600}}>
                                        <Form.Item name="keywords" label="关键词（每行一个）" rules={[{required: true, message: '请输入关键词'}]}
                                                   style={{marginBottom: 16}}>
                                            <Input.TextArea rows={3} placeholder={"jordan retro\nyeezy slides"} style={{borderRadius: 6}}/>
                                        </Form.Item>
                                        <Form.Item name="sorts" label="排序方式" initialValue={['lowest_ask']} style={{marginBottom: 16}}>
                                            <Select mode="multiple" placeholder="选择排序方式" options={sortOptions}/>
                                        </Form.Item>
                                        <div style={{display: 'flex', gap: 24, flexWrap: 'wrap'}}>
                                            <Form.Item name="pageCount" label="查询页数" initialValue={3} style={{marginBottom: 16}}>
                                                <InputNumber min={1} max={50} style={{width: 100}}/>
                                            </Form.Item>
                                            <Form.Item name="searchType" label="搜索类型" initialValue="shoes" style={{marginBottom: 16}}>
                                                <Radio.Group>
                                                    <Radio.Button value="shoes">鞋类</Radio.Button>
                                                    <Radio.Button value="clothes">服饰</Radio.Button>
                                                </Radio.Group>
                                            </Form.Item>
                                        </div>
                                        <Divider style={{margin: '8px 0 16px'}}/>
                                        <Space size="middle">
                                            <Button type="primary" onClick={handleStartSearchList} disabled={searchListState.running}>
                                                {searchListState.running ? '运行中...' : '开始搜索上架'}
                                            </Button>
                                            <Button danger onClick={handleCancelSearchList} disabled={!searchListState.running}>
                                                终止
                                            </Button>
                                            {searchListState.taskId && (
                                                <Button type="link" onClick={() => openTaskDetail(searchListState.taskId)}>查看明细</Button>
                                            )}
                                            {searchListState.running && <Badge status="processing" text="任务运行中"/>}
                                        </Space>
                                    </Form>
                                </div>
                            ),
                        },
                    ]}
                />
            )}
        </div>
    );

    return <>
        <Card bodyStyle={{padding: '16px 24px'}}>
            <Tabs
                defaultActiveKey="stockx"
                size="large"
                type="line"
                items={[
                    {key: 'stockx', label: 'StockX', children: stockxTab},
                    {key: 'kc', label: 'KickScrew', children: kcTab},
                ]}
            />
        </Card>

        <Modal title={previewTitle} open={previewVisible} onCancel={() => setPreviewVisible(false)} footer={null} width={600}>
            <Table
                dataSource={previewData}
                rowKey={(record) => `${record.styleId}:${record.size}`}
                size="small"
                pagination={{pageSize: 20, showTotal: (total) => `共 ${total} 条`}}
                columns={[
                    {title: '货号', dataIndex: 'styleId', key: 'styleId'},
                    {title: '尺码', dataIndex: 'size', key: 'size'},
                    {title: '最低价($)', dataIndex: 'minPrice', key: 'minPrice', render: (v: number) => v === -1 ? '跳过' : `$${v}`},
                ]}
            />
        </Modal>

        <TaskItemModal
            visible={taskItemModalVisible}
            taskId={currentViewTaskId}
            onClose={() => setTaskItemModalVisible(false)}
            defaultAutoRefresh={true}
        />
    </>
}

export default TaskExecutorPage;
