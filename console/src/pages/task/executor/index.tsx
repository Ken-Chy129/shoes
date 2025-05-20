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
    const [kcForm] = Form.useForm();
    const [stockForm] = Form.useForm();


    useEffect(() => {

    });

    const startKcTask = () => {
        doPostRequest(TASK_API.RUN_KC, {}, {
            onSuccess: _ => message.success("开始执行任务").then()
        });
    }


    return <>
        <Card title={"绿叉"}>
            <Form form={stockForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div>
                    <Form.Item label={"当前状态"} name={"status"}>
                        <Radio.Group
                            options={[
                                { value: true, label: '是' },
                                { value: false, label: '否' }
                            ]}
                        />
                    </Form.Item>
                    <Form.Item>
                        <Button onClick={startKcTask}>开启改价</Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"KC"}>
            <Form form={kcForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div>
                    <Form.Item label={"当前状态"} name={"status"}>
                        <Radio.Group
                            options={[
                                { value: true, label: '是' },
                                { value: false, label: '否' }
                            ]}
                        />
                    </Form.Item>
                    <Form.Item>
                        <Button>开启改价</Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
    </>
}

export default TaskExecutorPage;