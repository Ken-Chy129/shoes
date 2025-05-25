import {
    Button, Card, DatePicker,
    Form,
    Input,
    message,
    Modal,
    Radio,
} from "antd";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest} from "@/util/http";
import {TASK_API} from "@/services/task";

const TaskExecutorPage = () => {
    const [taskForm] = Form.useForm();
    const [kcTaskStatus, setKcTaskStatus] = useState<boolean>(false);

    useEffect(() => {
        queryTaskSetting();
        queryTaskStatus();
    });

    const queryTaskSetting = () => {
        doGetRequest(TASK_API.QUERY_TASK_SETTING, {}, {
            onSuccess: res => {
                taskForm.setFieldValue("kcTaskInterval", res.data.kcTaskInterval / 1000);
            }
        });
    }

    const updateTaskSetting = () => {
        const kcTaskInterval = taskForm.getFieldValue("kcTaskInterval") * 1000;
        doPostRequest(TASK_API.UPDATE_TASK_SETTING, {kcTaskInterval}, {
            onSuccess: res => setKcTaskStatus(res.data)
        });
    }

    const queryTaskStatus = () => {
        doGetRequest(TASK_API.QUERY_KC_TASK_STATUS, {}, {
            onSuccess: res => setKcTaskStatus(res.data)
        });
    }

    const startKcTask = () => {
        doPostRequest(TASK_API.RUN_KC, {}, {
            onSuccess: _ => message.success("开始执行任务").then()
        });
    }

    const stopKcTask = () => {
        doPostRequest(TASK_API.STOP_KC, {}, {
            onSuccess: _ => message.success("已暂停").then()
        });
    }

    const startStockxTask = () => {
        doPostRequest(TASK_API.RUN_KC, {}, {
            onSuccess: _ => message.success("开始执行任务").then()
        });
    }

    return <>
        {/*<Card title={"任务配置"}>*/}
        {/*    <Form form={taskForm}*/}
        {/*          style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>*/}
        {/*        <div style={{display: "flex"}}>*/}
        {/*            <Form.Item name="freight" label="任务运行间隔时间">*/}
        {/*                <Input/>*/}
        {/*            </Form.Item>*/}
        {/*            <Form.Item style={{marginLeft: 50}}>*/}
        {/*                <Button type="primary" htmlType="submit" >*/}
        {/*                    修改*/}
        {/*                </Button>*/}
        {/*            </Form.Item>*/}
        {/*        </div>*/}
        {/*    </Form>*/}
        {/*</Card>*/}
        {/*<br/>*/}
        {/*<Card title={"绿叉"}>*/}
        {/*    <Form style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>*/}
        {/*        <div style={{display: "flex"}}>*/}
        {/*            <Form.Item label={"当前状态"} name={"status"}>*/}
        {/*                {taskForm.getFieldValue("kcStatus")}*/}
        {/*            </Form.Item>*/}
        {/*            <Form.Item style={{marginLeft: 30}}>*/}
        {/*                <Button onClick={kcTaskStatus ? stopKcTask : startKcTask}>{kcTaskStatus ? "暂停改价": "开启改价"}</Button>*/}
        {/*            </Form.Item>*/}
        {/*        </div>*/}
        {/*    </Form>*/}
        {/*</Card>*/}
        {/*<br/>*/}
        <Card title={"KC"}>
            <Form form={taskForm} style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div>
                    <div style={{display: "flex"}}>
                        <Form.Item label={"任务间隔"} name="kcTaskInterval">
                            <Input/>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={updateTaskSetting}>修改配置</Button>
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button type="primary" onClick={kcTaskStatus ? stopKcTask : startKcTask}>{kcTaskStatus ? "暂停改价" : "开启改价"}</Button>
                        </Form.Item>
                    </div>
                </div>
            </Form>
        </Card>
    </>
}

export default TaskExecutorPage;