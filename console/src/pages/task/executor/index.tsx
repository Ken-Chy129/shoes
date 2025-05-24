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

    useEffect(() => {
        queryTaskStatus();
    });

    const queryTaskStatus = () => {
        taskForm.setFieldsValue(
            {"kcStatus": 1}
        );
    }

    const startStockxTask = () => {
        doPostRequest(TASK_API.RUN_KC, {}, {
            onSuccess: _ => message.success("开始执行任务").then()
        });
    }

    const startKcTask = () => {
        doPostRequest(TASK_API.RUN_KC, {}, {
            onSuccess: _ => message.success("开始执行任务").then()
        });
    }


    return <>
        <Card title={"任务配置"}>
            <Form form={taskForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="freight" label="任务运行间隔时间">
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" >
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"绿叉"}>
            <Form style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item label={"当前状态"} name={"status"}>
                        {taskForm.getFieldValue("kcStatus")}
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button onClick={startKcTask}>开启改价</Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"KC"}>
            <Form style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div>
                    <div style={{display: "flex"}}>
                        <Form.Item label={"当前状态"} name={"status"}>
                            123
                        </Form.Item>
                        <Form.Item style={{marginLeft: 30}}>
                            <Button onClick={startKcTask}>开启改价</Button>
                        </Form.Item>
                    </div>
                </div>
            </Form>
        </Card>
    </>
}

export default TaskExecutorPage;