import {
    Button, Card,
    Form,
    Input,
    InputNumber,
    message,
    Select,
    Divider,
} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {TASK_API, TASK_TYPE} from "@/services/task";

interface SortOption {
    value: string;
    label: string;
}

const TaskExecutorPage = () => {
    const [taskForm] = Form.useForm();
    const [configForm] = Form.useForm();
    const [kcTaskStatus, setKcTaskStatus] = useState<boolean>(false);
    // const [stockxListingTaskStatus, setStockxListingTaskStatus] = useState<boolean>(false);
    const [stockxPriceDownTaskStatus, setStockxPriceDownTaskStatus] = useState<boolean>(false);
    const [sortOptions, setSortOptions] = useState<SortOption[]>([]);

    useEffect(() => {
        queryAllTaskStatus();
        queryAllTaskInterval();
        queryStockXConfig();
        querySortOptions();
    }, []);

    // ==================== 统一查询方法 ====================

    const queryAllTaskStatus = () => {
        queryTaskStatus(TASK_TYPE.KC, setKcTaskStatus);
        // queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus);
        queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus);
    }

    const queryAllTaskInterval = () => {
        queryTaskInterval(TASK_TYPE.KC, "kcTaskInterval");
        // queryTaskInterval(TASK_TYPE.STOCKX_LISTING, "stockxListingTaskInterval");
        queryTaskInterval(TASK_TYPE.STOCKX_PRICE_DOWN, "stockxPriceDownTaskInterval");
    }

    const queryTaskStatus = (taskType: string, setStatus: (status: boolean) => void) => {
        doGetRequest(TASK_API.STATUS, {taskType}, {
            onSuccess: res => setStatus(res.data)
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

    const stopTask = (taskType: string, onFinally: () => void) => {
        doPostRequest(`${TASK_API.STOP}?taskType=${taskType}`, {}, {
            onSuccess: _ => message.success("已暂停").then(),
            onFinally
        });
    }

    const cancelTask = (taskType: string, onFinally: () => void) => {
        doPostRequest(`${TASK_API.CANCEL}?taskType=${taskType}`, {}, {
            onSuccess: _ => message.success("已取消").then(),
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

    const handleKcTask = () => {
        if (kcTaskStatus) {
            stopTask(TASK_TYPE.KC, () => queryTaskStatus(TASK_TYPE.KC, setKcTaskStatus));
        } else {
            startTask(TASK_TYPE.KC, () => queryTaskStatus(TASK_TYPE.KC, setKcTaskStatus));
        }
    }

    const handleKcCancel = () => {
        cancelTask(TASK_TYPE.KC, () => queryTaskStatus(TASK_TYPE.KC, setKcTaskStatus));
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

    const handleStockxPriceDownTask = () => {
        if (stockxPriceDownTaskStatus) {
            stopTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus));
        } else {
            startTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus));
        }
    }

    const handleStockxPriceDownCancel = () => {
        cancelTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus));
    }

    return <>
        <Card title={"KC"}>
            <Form form={taskForm} style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div>
                    <div style={{display: "flex", alignItems: "center"}}>
                        <Form.Item label={"任务间隔"} name="kcTaskInterval">
                            <Input/>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={() => updateTaskInterval(TASK_TYPE.KC, "kcTaskInterval")}>修改配置</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button type="primary" onClick={handleKcTask}>{kcTaskStatus ? "暂停改价" : "开启改价"}</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 15}}>
                            <Button danger onClick={handleKcCancel} disabled={!kcTaskStatus}>取消任务</Button>
                        </Form.Item>
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
                            <Button type="primary" onClick={handleStockxPriceDownTask}>
                                {stockxPriceDownTaskStatus ? "暂停压价" : "开启压价"}
                            </Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 15}}>
                            <Button danger onClick={handleStockxPriceDownCancel} disabled={!stockxPriceDownTaskStatus}>取消任务</Button>
                        </Form.Item>
                    </div>
                </div>
            </Form>
        </Card>
    </>
}

export default TaskExecutorPage;
