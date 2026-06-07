import {
    Button, Card,
    Form,
    Input,
    InputNumber,
    message,
    Select,
    Divider,
    Upload,
    Tag,
    Table,
    Modal,
} from "antd";
import {UploadOutlined, EyeOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest, doUploadRequestWithParams} from "@/util/http";
import {TASK_API, TASK_TYPE} from "@/services/task";
import {SETTING_API} from "@/services/shoes";
import TaskItemModal from "@/pages/task/components/TaskItemModal";

interface SortOption {
    value: string;
    label: string;
}

const TaskExecutorPage = () => {
    const [taskForm] = Form.useForm();
    const [configForm] = Form.useForm();
    const [kcTaskStatus, setKcTaskStatus] = useState<boolean>(false);
    const [kcPriceDownTaskStatus, setKcPriceDownTaskStatus] = useState<boolean>(false);
    const [sortOptions, setSortOptions] = useState<SortOption[]>([]);

    // StockX 多账号
    const [stockxAccounts, setStockxAccounts] = useState<any[]>([]);
    // key: "accountId:inventoryType", value: { running, taskId, excelCount, interval }
    const [excelTaskStates, setExcelTaskStates] = useState<Record<string, any>>({});

    // Excel 预览
    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewData, setPreviewData] = useState<any[]>([]);
    const [previewTitle, setPreviewTitle] = useState('');

    // 当前任务ID (使用string避免精度丢失)
    const [kcCurrentTaskId, setKcCurrentTaskId] = useState<string | null>(null);
    const [kcPriceDownCurrentTaskId, setKcPriceDownCurrentTaskId] = useState<string | null>(null);
    // 任务明细弹窗
    const [taskItemModalVisible, setTaskItemModalVisible] = useState(false);
    const [currentViewTaskId, setCurrentViewTaskId] = useState<string | null>(null);

    useEffect(() => {
        queryAllTaskStatus();
        queryAllTaskInterval();
        queryStockXConfig();
        querySortOptions();
        loadStockXAccounts();
    }, []);

    // ==================== 统一查询方法 ====================

    const queryAllTaskStatus = () => {
        queryTaskStatus(TASK_TYPE.KC_LISTING, setKcTaskStatus, setKcCurrentTaskId);
        queryTaskStatus(TASK_TYPE.KC_PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId);
    }

    const queryAllTaskInterval = () => {
        queryTaskInterval(TASK_TYPE.KC_PRICE_DOWN, "kcPriceDownTaskInterval");
    }

    const loadStockXAccounts = () => {
        doGetRequest(SETTING_API.STOCKX_ACCOUNTS, {}, {
            onSuccess: res => {
                const accounts = (res.data || []).filter((a: any) => a.enabled);
                setStockxAccounts(accounts);
                accounts.forEach((account: any) => {
                    ['STANDARD', 'CUSTODIAL'].forEach(inventoryType => {
                        refreshExcelTaskState(account.name, inventoryType);
                    });
                });
            }
        });
    }

    const refreshExcelTaskState = (accountId: string, inventoryType: string) => {
        const key = `${accountId}:${inventoryType}`;
        doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_COUNT, {accountId, inventoryType}, {
            onSuccess: res => {
                setExcelTaskStates(prev => ({
                    ...prev,
                    [key]: {...(prev[key] || {}), excelCount: res.data || 0}
                }));
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

    const queryTaskStatus = (taskType: string, setStatus: (status: boolean) => void, setTaskId?: (id: string | null) => void) => {
        doGetRequest(TASK_API.STATUS, {taskType}, {
            onSuccess: res => {
                setStatus(res.data);
                // 如果任务正在运行，查询当前任务ID
                if (res.data && setTaskId) {
                    queryCurrentTaskId(taskType, setTaskId);
                } else if (setTaskId) {
                    setTaskId(null);
                }
            }
        });
    }

    const queryCurrentTaskId = (taskType: string, setTaskId: (id: string | null) => void) => {
        doGetRequest(TASK_API.CURRENT_TASK_ID, {taskType}, {
            onSuccess: res => setTaskId(res.data)
        });
    }

    const queryTaskInterval = (taskType: string, fieldName: string) => {
        doGetRequest(TASK_API.INTERVAL, {taskType}, {
            onSuccess: res => taskForm.setFieldValue(fieldName, res.data / 1000)
        });
    }

    const startTask = (taskType: string, onFinally: () => void) => {
        doPostRequest(`${TASK_API.START}?taskType=${taskType}`, {}, {
            onSuccess: _ => message.success("开始执行任务").then(),
            onFinally
        });
    }

    const cancelTask = (taskType: string, onFinally: () => void) => {
        doPostRequest(`${TASK_API.CANCEL}?taskType=${taskType}`, {}, {
            onSuccess: _ => message.success("已终止").then(),
            onFinally
        });
    }

    const updateTaskInterval = (taskType: string, fieldName: string) => {
        const interval = taskForm.getFieldValue(fieldName) * 1000;
        doPostRequest(`${TASK_API.INTERVAL}?taskType=${taskType}&interval=${interval}`, {}, {
            onSuccess: _ => message.success("配置已更新").then()
        });
    }

    // ==================== StockX 任务配置 ====================

    const queryStockXConfig = () => {
        doGetRequest(TASK_API.STOCKX_CONFIG, {}, {
            onSuccess: res => {
                const { listingSort, listingOrder } = res.data;
                configForm.setFieldsValue({
                    priceDownThreadCount: res.data.priceDownThreadCount,
                    priceDownPerMinute: res.data.priceDownPerMinute,
                    // 合并 sort 和 order 为一个值
                    listingSortOption: `${listingSort}_${listingOrder}`,
                });
            }
        });
    }

    const querySortOptions = () => {
        doGetRequest(TASK_API.STOCKX_SORT_OPTIONS, {}, {
            onSuccess: res => setSortOptions(res.data)
        });
    }

    const updateStockXConfig = () => {
        const values = configForm.getFieldsValue();
        // 拆分 sortOption 为 sort 和 order
        const sortOption = values.listingSortOption || "CREATED_AT_DESC";
        const lastUnderscoreIndex = sortOption.lastIndexOf('_');
        const listingSort = sortOption.substring(0, lastUnderscoreIndex);
        const listingOrder = sortOption.substring(lastUnderscoreIndex + 1);

        const config = {
            priceDownThreadCount: values.priceDownThreadCount,
            priceDownPerMinute: values.priceDownPerMinute,
            listingSort,
            listingOrder,
        };
        doPostRequest(TASK_API.STOCKX_CONFIG, config, {
            onSuccess: _ => message.success("配置已保存").then()
        });
    }

    // ==================== KC 任务 ====================

    const handleKcStart = () => {
        startTask(TASK_TYPE.KC_LISTING, () => queryTaskStatus(TASK_TYPE.KC_LISTING, setKcTaskStatus, setKcCurrentTaskId));
    }

    const handleKcCancel = () => {
        cancelTask(TASK_TYPE.KC_LISTING, () => queryTaskStatus(TASK_TYPE.KC_LISTING, setKcTaskStatus, setKcCurrentTaskId));
    }

    const handleViewKcTaskDetail = () => {
        if (kcCurrentTaskId) {
            setCurrentViewTaskId(kcCurrentTaskId);
            setTaskItemModalVisible(true);
        }
    }

    // ==================== KC 压价任务 ====================

    const handleKcPriceDownStart = () => {
        startTask(TASK_TYPE.KC_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.KC_PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId));
    }

    const handleKcPriceDownCancel = () => {
        cancelTask(TASK_TYPE.KC_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.KC_PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId));
    }

    const handleViewKcPriceDownTaskDetail = () => {
        if (kcPriceDownCurrentTaskId) {
            setCurrentViewTaskId(kcPriceDownCurrentTaskId);
            setTaskItemModalVisible(true);
        }
    }

    // ==================== StockX Excel 多账号压价 ====================

    const handlePreviewExcel = (accountId: string, inventoryType: string) => {
        doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_DATA, {accountId, inventoryType}, {
            onSuccess: res => {
                setPreviewData(res.data || []);
                setPreviewTitle(`${inventoryType === 'STANDARD' ? '现货' : '寄存'}压价 Excel 数据`);
                setPreviewVisible(true);
            }
        });
    }

    const handleUploadExcel = (file: any, accountId: string, inventoryType: string) => {
        doUploadRequestWithParams(TASK_API.STOCKX_UPLOAD_PRICE_DOWN_EXCEL, file, {accountId, inventoryType}, {
            onSuccess: res => {
                message.success(`已加载 ${res.data} 条数据`);
                refreshExcelTaskState(accountId, inventoryType);
            },
            onError: () => message.error('上传失败'),
        });
        return false;
    }

    const handleExcelPriceDownStart = (accountId: string, inventoryType: string) => {
        doPostRequest(TASK_API.STOCKX_START_EXCEL_PRICE_DOWN, {accountId, inventoryType}, {
            onSuccess: () => {
                message.success('任务已启动');
                refreshExcelTaskState(accountId, inventoryType);
            }
        });
    }

    const handleExcelPriceDownCancel = (accountId: string, inventoryType: string) => {
        doPostRequest(TASK_API.STOCKX_CANCEL_EXCEL_PRICE_DOWN, {accountId, inventoryType}, {
            onSuccess: () => {
                message.success('已发送取消信号');
                setTimeout(() => refreshExcelTaskState(accountId, inventoryType), 2000);
            }
        });
    }

    const handleSetExcelInterval = (accountId: string, inventoryType: string, interval: number) => {
        doPostRequest(TASK_API.STOCKX_SET_EXCEL_PRICE_DOWN_INTERVAL, {accountId, inventoryType, interval}, {
            onSuccess: () => message.success('间隔已保存')
        });
    }

    const handleViewExcelTaskDetail = (taskId: string) => {
        setCurrentViewTaskId(taskId);
        setTaskItemModalVisible(true);
    }

    const getExcelState = (accountId: string, inventoryType: string) => {
        return excelTaskStates[`${accountId}:${inventoryType}`] || {};
    }

    return <>
        <Card title={"KC"}>
            <Form form={taskForm}>
                <div style={{marginBottom: 16}}>
                    <div style={{fontWeight: "bold", marginBottom: 8}}>上架</div>
                    <div style={{display: "flex", alignItems: "center"}}>
                        <Form.Item style={{marginLeft: 0}}>
                            <Button type="primary" onClick={handleKcStart} disabled={kcTaskStatus}>开启上架</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 15}}>
                            <Button danger onClick={handleKcCancel} disabled={!kcTaskStatus}>终止任务</Button>
                        </Form.Item>
                        {kcTaskStatus && kcCurrentTaskId && (
                            <Form.Item style={{marginLeft: 15}}>
                                <Button type="link" onClick={handleViewKcTaskDetail}>查看明细</Button>
                            </Form.Item>
                        )}
                    </div>
                </div>
                <Divider/>
                <div>
                    <div style={{fontWeight: "bold", marginBottom: 8}}>压价</div>
                    <div style={{display: "flex", alignItems: "center"}}>
                        <Form.Item label={"任务间隔"} name="kcPriceDownTaskInterval">
                            <Input/>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={() => updateTaskInterval(TASK_TYPE.KC_PRICE_DOWN, "kcPriceDownTaskInterval")}>修改配置</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button type="primary" onClick={handleKcPriceDownStart} disabled={kcPriceDownTaskStatus}>开启压价</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 15}}>
                            <Button danger onClick={handleKcPriceDownCancel} disabled={!kcPriceDownTaskStatus}>终止任务</Button>
                        </Form.Item>
                        {kcPriceDownTaskStatus && kcPriceDownCurrentTaskId && (
                            <Form.Item style={{marginLeft: 15}}>
                                <Button type="link" onClick={handleViewKcPriceDownTaskDetail}>查看明细</Button>
                            </Form.Item>
                        )}
                    </div>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"StockX"}>
            {/* 任务配置区域 */}
            <Form form={configForm} layout="inline">
                <Form.Item label={"压价线程数"} name="priceDownThreadCount">
                    <InputNumber min={1} max={10} style={{width: 80}}/>
                </Form.Item>
                <Form.Item label={"每分钟压价数"} name="priceDownPerMinute">
                    <InputNumber min={1} max={200} style={{width: 80}}/>
                </Form.Item>
                <Form.Item label={"压价排序"} name="listingSortOption">
                    <Select style={{width: 180}} options={sortOptions}/>
                </Form.Item>
                <Form.Item>
                    <Button type="primary" onClick={updateStockXConfig}>保存配置</Button>
                </Form.Item>
            </Form>

        </Card>
        <br/>
        <Card title={"StockX Excel 压价（多账号）"}>
            {stockxAccounts.length === 0 && <div style={{color: '#999'}}>暂无已启用的 StockX 账号，请在首页配置中添加</div>}
            {stockxAccounts.map((account: any, idx: number) => (
                <div key={account.name}>
                    {idx > 0 && <Divider/>}
                    <div style={{fontWeight: "bold", fontSize: 15, marginBottom: 12}}>{account.name}</div>
                    {['STANDARD', 'CUSTODIAL'].map((inventoryType) => {
                        const state = getExcelState(account.name, inventoryType);
                        const excelCount = state.excelCount || 0;
                        const running = state.running || false;
                        const taskId = state.taskId || null;
                        const interval = state.interval || 1800;
                        const label = inventoryType === 'STANDARD' ? '现货' : '寄存';
                        const color = inventoryType === 'STANDARD' ? 'green' : 'blue';
                        return (
                            <div key={inventoryType} style={{marginBottom: 12, marginLeft: 16}}>
                                <div style={{fontWeight: "bold", marginBottom: 6}}>
                                    {label} ({inventoryType})
                                    {excelCount > 0 && <Tag color={color} style={{marginLeft: 8}}>已加载 {excelCount} 条</Tag>}
                                </div>
                                <div style={{display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap"}}>
                                    <Upload
                                        accept=".xlsx,.xls"
                                        beforeUpload={(file) => handleUploadExcel(file, account.name, inventoryType)}
                                        showUploadList={false}
                                    >
                                        <Button icon={<UploadOutlined/>} size="small">上传Excel</Button>
                                    </Upload>
                                    {excelCount > 0 && (
                                        <Button icon={<EyeOutlined/>} size="small"
                                                onClick={() => handlePreviewExcel(account.name, inventoryType)}>预览</Button>
                                    )}
                                    <InputNumber
                                        min={10} size="small" style={{width: 120}}
                                        value={interval}
                                        addonAfter="秒"
                                        onChange={(val) => {
                                            if (!val) return;
                                            const key = `${account.name}:${inventoryType}`;
                                            setExcelTaskStates(prev => ({
                                                ...prev,
                                                [key]: {...(prev[key] || {}), interval: val}
                                            }));
                                            handleSetExcelInterval(account.name, inventoryType, val);
                                        }}
                                    />
                                    <Button type="primary" size="small"
                                            onClick={() => handleExcelPriceDownStart(account.name, inventoryType)}
                                            disabled={running || excelCount === 0}>
                                        开启压价
                                    </Button>
                                    <Button danger size="small"
                                            onClick={() => handleExcelPriceDownCancel(account.name, inventoryType)}
                                            disabled={!running}>
                                        终止
                                    </Button>
                                    {taskId && (
                                        <Button type="link" size="small"
                                                onClick={() => handleViewExcelTaskDetail(taskId)}>查看明细</Button>
                                    )}
                                </div>
                            </div>
                        );
                    })}
                </div>
            ))}
        </Card>

        <Modal title={previewTitle} open={previewVisible} onCancel={() => setPreviewVisible(false)}
               footer={null} width={600}>
            <Table
                dataSource={previewData}
                rowKey={(record) => `${record.styleId}:${record.size}`}
                size="small"
                pagination={{pageSize: 20, showTotal: (total) => `共 ${total} 条`}}
                columns={[
                    {title: '货号', dataIndex: 'styleId', key: 'styleId'},
                    {title: '尺码', dataIndex: 'size', key: 'size'},
                    {title: '最低价($)', dataIndex: 'minPrice', key: 'minPrice'},
                    {title: '比价方式', dataIndex: 'compareType', key: 'compareType'},
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
