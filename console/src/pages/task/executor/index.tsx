import {
    Button, Card,
    Form,
    Input,
    message,
} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {TASK_API, TASK_TYPE} from "@/services/task";

const TaskExecutorPage = () => {
    const [taskForm] = Form.useForm();
    const [kcTaskStatus, setKcTaskStatus] = useState<boolean>(false);
    const [stockxListingTaskStatus, setStockxListingTaskStatus] = useState<boolean>(false);
    const [stockxPriceDownTaskStatus, setStockxPriceDownTaskStatus] = useState<boolean>(false);

    useEffect(() => {
        queryAllTaskStatus();
        queryAllTaskInterval();
    }, []);

    // ==================== 统一查询方法 ====================

    const queryAllTaskStatus = () => {
        queryTaskStatus(TASK_TYPE.KC, setKcTaskStatus);
        queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus);
        queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus);
    }

    const queryAllTaskInterval = () => {
        queryTaskInterval(TASK_TYPE.KC, "kcTaskInterval");
        queryTaskInterval(TASK_TYPE.STOCKX_LISTING, "stockxListingTaskInterval");
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

    const updateTaskInterval = (taskType: string, fieldName: string) => {
        const interval = taskForm.getFieldValue(fieldName) * 1000;
        doPostRequest(`${TASK_API.INTERVAL}?taskType=${taskType}&interval=${interval}`, {}, {
            onSuccess: _ => message.success("配置已更新").then()
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

    // ==================== StockX 上架任务 ====================

    const handleStockxListingTask = () => {
        if (stockxListingTaskStatus) {
            stopTask(TASK_TYPE.STOCKX_LISTING, () => queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus));
        } else {
            startTask(TASK_TYPE.STOCKX_LISTING, () => queryTaskStatus(TASK_TYPE.STOCKX_LISTING, setStockxListingTaskStatus));
        }
    }

    // ==================== StockX 压价任务 ====================

    const handleStockxPriceDownTask = () => {
        if (stockxPriceDownTaskStatus) {
            stopTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus));
        } else {
            startTask(TASK_TYPE.STOCKX_PRICE_DOWN, () => queryTaskStatus(TASK_TYPE.STOCKX_PRICE_DOWN, setStockxPriceDownTaskStatus));
        }
    }

    return <>
        <Card title={"KC"}>
            <Form form={taskForm} style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div>
                    <div style={{display: "flex"}}>
                        <Form.Item label={"任务间隔"} name="kcTaskInterval">
                            <Input/>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={() => updateTaskInterval(TASK_TYPE.KC, "kcTaskInterval")}>修改配置</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button type="primary" onClick={handleKcTask}>{kcTaskStatus ? "暂停改价" : "开启改价"}</Button>
                        </Form.Item>
                    </div>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"StockX"}>
            <Form form={taskForm}>
                {/* 上架任务 */}
                <div style={{marginBottom: 16}}>
                    <div style={{fontWeight: "bold", marginBottom: 8}}>上架</div>
                    <div style={{display: "flex"}}>
                        <Form.Item label={"任务间隔"} name="stockxListingTaskInterval">
                            <Input/>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={() => updateTaskInterval(TASK_TYPE.STOCKX_LISTING, "stockxListingTaskInterval")}>修改配置</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button type="primary" onClick={handleStockxListingTask}>
                                {stockxListingTaskStatus ? "暂停上架" : "开启上架"}
                            </Button>
                        </Form.Item>
                    </div>
                </div>
                {/* 压价任务 */}
                <div>
                    <div style={{fontWeight: "bold", marginBottom: 8}}>压价</div>
                    <div style={{display: "flex"}}>
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
                    </div>
                </div>
            </Form>
        </Card>
    </>
}

export default TaskExecutorPage;
