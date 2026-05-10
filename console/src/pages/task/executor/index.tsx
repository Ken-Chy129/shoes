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
    // const [stockxListingTaskStatus, setStockxListingTaskStatus] = useState<boolean>(false);
    const [stockxPriceDownTaskStatus, setStockxPriceDownTaskStatus] = useState<boolean>(false);
    const [sortOptions, setSortOptions] = useState<SortOption[]>([]);

    // StockX Excel压价
    const [stockxStandardPriceDownStatus, setStockxStandardPriceDownStatus] = useState<boolean>(false);
    const [stockxCustodialPriceDownStatus, setStockxCustodialPriceDownStatus] = useState<boolean>(false);
    const [standardExcelCount, setStandardExcelCount] = useState<number>(0);
    const [custodialExcelCount, setCustodialExcelCount] = useState<number>(0);

    // Excel 预览
    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewData, setPreviewData] = useState<any[]>([]);
    const [previewTitle, setPreviewTitle] = useState('');

    // 当前任务ID (使用string避免精度丢失)
    const [kcCurrentTaskId, setKcCurrentTaskId] = useState<string | null>(null);
    const [kcPriceDownCurrentTaskId, setKcPriceDownCurrentTaskId] = useState<string | null>(null);
    const [stockxPriceDownCurrentTaskId, setStockxPriceDownCurrentTaskId] = useState<string | null>(null);
    const [stockxStandardPriceDownTaskId, setStockxStandardPriceDownTaskId] = useState<string | null>(null);
    const [stockxCustodialPriceDownTaskId, setStockxCustodialPriceDownTaskId] = useState<string | null>(null);

    // 任务明细弹窗
    const [taskItemModalVisible, setTaskItemModalVisible] = useState(false);
    const [currentViewTaskId, setCurrentViewTaskId] = useState<string | null>(null);

    useEffect(() => {
        queryAllTaskStatus();
        queryAllTaskInterval();
        queryStockXConfig();
        querySortOptions();
        queryExcelCounts();
    }, []);

    // ==================== 统一查询方法 ====================

    const queryAllTaskStatus = () => {
        queryTaskStatus(TASK_TYPE.KC_LISTING, setKcTaskStatus, setKcCurrentTaskId);
        queryTaskStatus(TASK_TYPE.KC_PRICE_DOWN, setKcPriceDownTaskStatus, setKcPriceDownCurrentTaskId);
        queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus, setStockxPriceDownCurrentTaskId);
        queryTaskStatus(TASK_TYPE.STOCKX_STANDARD_PRICE_DOWN, setStockxStandardPriceDownStatus, setStockxStandardPriceDownTaskId);
        queryTaskStatus(TASK_TYPE.STOCKX_CUSTODIAL_PRICE_DOWN, setStockxCustodialPriceDownStatus, setStockxCustodialPriceDownTaskId);
    }

    const queryAllTaskInterval = () => {
        queryTaskInterval(TASK_TYPE.KC_PRICE_DOWN, "kcPriceDownTaskInterval");
        queryTaskInterval(TASK_TYPE.STOCKX_PRICE_DOWN, "stockxPriceDownTaskInterval");
    }

    const queryExcelCounts = () => {
        doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_COUNT, {inventoryType: 'STANDARD'}, {
            onSuccess: res => setStandardExcelCount(res.data || 0)
        });
        doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_COUNT, {inventoryType: 'CUSTODIAL'}, {
            onSuccess: res => setCustodialExcelCount(res.data || 0)
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

    // ==================== StockX 上架任务 ====================

    // const handleStockxListingTask = () => {
    //     if (stockxListingTaskStatus) {
    //         stopTask(TASK_TYPE.STOCKX_LISTING, () => queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus));
    //     } else {
    //         startTask(TASK_TYPE.STOCKX_LISTING, () => queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus));
    //     }
    // }

    // const handleStockxListingCancel = () => {
    //     cancelTask(TASK_TYPE.STOCKX_LISTING, () => queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus));
    // }

    // ==================== StockX 压价任务 ====================

    const handleStockxPriceDownStart = () => {
        startTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus, setStockxPriceDownCurrentTaskId));
    }

    const handleStockxPriceDownCancel = () => {
        cancelTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus, setStockxPriceDownCurrentTaskId));
    }

    const handleViewStockxPriceDownTaskDetail = () => {
        if (stockxPriceDownCurrentTaskId) {
            setCurrentViewTaskId(stockxPriceDownCurrentTaskId);
            setTaskItemModalVisible(true);
        }
    }

    // ==================== StockX Excel 压价 ====================

    const handlePreviewExcel = (inventoryType: string) => {
        doGetRequest(TASK_API.STOCKX_PRICE_DOWN_EXCEL_DATA, {inventoryType}, {
            onSuccess: res => {
                setPreviewData(res.data || []);
                setPreviewTitle(inventoryType === 'STANDARD' ? '现货压价 Excel 数据' : '寄存压价 Excel 数据');
                setPreviewVisible(true);
            }
        });
    }

    const handleUploadExcel = (file: any, inventoryType: string) => {
        doUploadRequestWithParams(TASK_API.STOCKX_UPLOAD_PRICE_DOWN_EXCEL, file, {inventoryType}, {
            onSuccess: res => {
                message.success(`已加载 ${res.data} 条数据`);
                queryExcelCounts();
            },
            onError: () => message.error('上传失败'),
        });
        return false;
    }

    const handleStandardPriceDownStart = () => {
        if (standardExcelCount === 0) {
            message.warning('请先上传现货压价Excel');
            return;
        }
        startTask(TASK_TYPE.STOCKX_STANDARD_PRICE_DOWN, () =>
            queryTaskStatus(TASK_TYPE.STOCKX_STANDARD_PRICE_DOWN, setStockxStandardPriceDownStatus, setStockxStandardPriceDownTaskId));
    }

    const handleStandardPriceDownCancel = () => {
        cancelTask(TASK_TYPE.STOCKX_STANDARD_PRICE_DOWN, () =>
            queryTaskStatus(TASK_TYPE.STOCKX_STANDARD_PRICE_DOWN, setStockxStandardPriceDownStatus, setStockxStandardPriceDownTaskId));
    }

    const handleCustodialPriceDownStart = () => {
        if (custodialExcelCount === 0) {
            message.warning('请先上传寄存压价Excel');
            return;
        }
        startTask(TASK_TYPE.STOCKX_CUSTODIAL_PRICE_DOWN, () =>
            queryTaskStatus(TASK_TYPE.STOCKX_CUSTODIAL_PRICE_DOWN, setStockxCustodialPriceDownStatus, setStockxCustodialPriceDownTaskId));
    }

    const handleCustodialPriceDownCancel = () => {
        cancelTask(TASK_TYPE.STOCKX_CUSTODIAL_PRICE_DOWN, () =>
            queryTaskStatus(TASK_TYPE.STOCKX_CUSTODIAL_PRICE_DOWN, setStockxCustodialPriceDownStatus, setStockxCustodialPriceDownTaskId));
    }

    const handleViewStandardPriceDownDetail = () => {
        if (stockxStandardPriceDownTaskId) {
            setCurrentViewTaskId(stockxStandardPriceDownTaskId);
            setTaskItemModalVisible(true);
        }
    }

    const handleViewCustodialPriceDownDetail = () => {
        if (stockxCustodialPriceDownTaskId) {
            setCurrentViewTaskId(stockxCustodialPriceDownTaskId);
            setTaskItemModalVisible(true);
        }
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

            <Divider/>

            <Form form={taskForm}>
                {/* 上架任务（暂时注释） */}
                {/*<div style={{marginBottom: 16}}>*/}
                {/*    <div style={{fontWeight: "bold", marginBottom: 8}}>上架</div>*/}
                {/*    <div style={{display: "flex", alignItems: "center"}}>*/}
                {/*        <Form.Item label={"任务间隔"} name="stockxListingTaskInterval">*/}
                {/*            <Input/>*/}
                {/*        </Form.Item>*/}
                {/*        <Form.Item style={{marginLeft: 30}}>*/}
                {/*            <Button onClick={() => updateTaskInterval(TASK_TYPE.STOCKX_LISTING, "stockxListingTaskInterval")}>修改配置</Button>*/}
                {/*        </Form.Item>*/}
                {/*        <Form.Item style={{marginLeft: 30}}>*/}
                {/*            <Button type="primary" onClick={handleStockxListingTask}>*/}
                {/*                {stockxListingTaskStatus ? "暂停上架" : "开启上架"}*/}
                {/*            </Button>*/}
                {/*        </Form.Item>*/}
                {/*        <Form.Item style={{marginLeft: 15}}>*/}
                {/*            <Button danger onClick={handleStockxListingCancel} disabled={!stockxListingTaskStatus}>取消任务</Button>*/}
                {/*        </Form.Item>*/}
                {/*    </div>*/}
                {/*</div>*/}
                {/* 压价任务 */}
                <div>
                    <div style={{fontWeight: "bold", marginBottom: 8}}>压价</div>
                    <div style={{display: "flex", alignItems: "center"}}>
                        <Form.Item label={"任务间隔"} name="stockxPriceDownTaskInterval">
                            <Input/>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={() => updateTaskInterval(TASK_TYPE.STOCKX_PRICE_DOWN, "stockxPriceDownTaskInterval")}>修改配置</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button type="primary" onClick={handleStockxPriceDownStart} disabled={stockxPriceDownTaskStatus}>开启压价</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 15}}>
                            <Button danger onClick={handleStockxPriceDownCancel} disabled={!stockxPriceDownTaskStatus}>终止任务</Button>
                        </Form.Item>
                        {stockxPriceDownTaskStatus && stockxPriceDownCurrentTaskId && (
                            <Form.Item style={{marginLeft: 15}}>
                                <Button type="link" onClick={handleViewStockxPriceDownTaskDetail}>查看明细</Button>
                            </Form.Item>
                        )}
                    </div>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"StockX Excel 压价"}>
            {/* 现货压价 */}
            <div style={{marginBottom: 16}}>
                <div style={{fontWeight: "bold", marginBottom: 8}}>
                    现货压价 (STANDARD)
                    {standardExcelCount > 0 && <Tag color="green" style={{marginLeft: 8}}>已加载 {standardExcelCount} 条</Tag>}
                </div>
                <div style={{display: "flex", alignItems: "center", gap: 12}}>
                    <Upload
                        accept=".xlsx,.xls"
                        beforeUpload={(file) => handleUploadExcel(file, 'STANDARD')}
                        showUploadList={false}
                    >
                        <Button icon={<UploadOutlined/>}>上传Excel</Button>
                    </Upload>
                    {standardExcelCount > 0 && (
                        <Button icon={<EyeOutlined/>} onClick={() => handlePreviewExcel('STANDARD')}>预览数据</Button>
                    )}
                    <Button type="primary" onClick={handleStandardPriceDownStart}
                            disabled={stockxStandardPriceDownStatus || standardExcelCount === 0}>
                        开启压价
                    </Button>
                    <Button danger onClick={handleStandardPriceDownCancel} disabled={!stockxStandardPriceDownStatus}>
                        终止任务
                    </Button>
                    {stockxStandardPriceDownStatus && stockxStandardPriceDownTaskId && (
                        <Button type="link" onClick={handleViewStandardPriceDownDetail}>查看明细</Button>
                    )}
                </div>
            </div>

            <Divider/>

            {/* 寄存压价 */}
            <div>
                <div style={{fontWeight: "bold", marginBottom: 8}}>
                    寄存压价 (CUSTODIAL)
                    {custodialExcelCount > 0 && <Tag color="blue" style={{marginLeft: 8}}>已加载 {custodialExcelCount} 条</Tag>}
                </div>
                <div style={{display: "flex", alignItems: "center", gap: 12}}>
                    <Upload
                        accept=".xlsx,.xls"
                        beforeUpload={(file) => handleUploadExcel(file, 'CUSTODIAL')}
                        showUploadList={false}
                    >
                        <Button icon={<UploadOutlined/>}>上传Excel</Button>
                    </Upload>
                    {custodialExcelCount > 0 && (
                        <Button icon={<EyeOutlined/>} onClick={() => handlePreviewExcel('CUSTODIAL')}>预览数据</Button>
                    )}
                    <Button type="primary" onClick={handleCustodialPriceDownStart}
                            disabled={stockxCustodialPriceDownStatus || custodialExcelCount === 0}>
                        开启压价
                    </Button>
                    <Button danger onClick={handleCustodialPriceDownCancel} disabled={!stockxCustodialPriceDownStatus}>
                        终止任务
                    </Button>
                    {stockxCustodialPriceDownStatus && stockxCustodialPriceDownTaskId && (
                        <Button type="link" onClick={handleViewCustodialPriceDownDetail}>查看明细</Button>
                    )}
                </div>
            </div>
        </Card>

        <Modal title={previewTitle} open={previewVisible} onCancel={() => setPreviewVisible(false)}
               footer={null} width={600}>
            <Table
                dataSource={previewData}
                rowKey={(record) => `${record.styleId}:${record.euSize}`}
                size="small"
                pagination={{pageSize: 20, showTotal: (total) => `共 ${total} 条`}}
                columns={[
                    {title: '货号', dataIndex: 'styleId', key: 'styleId'},
                    {title: '尺码', dataIndex: 'euSize', key: 'euSize'},
                    {title: '最低价($)', dataIndex: 'minPrice', key: 'minPrice'},
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
