import {
    Button, Card, DatePicker,
    Form,
    Input,
    message,
    Modal,
    Radio,
    Select,
    Space,
    Table,
    Tabs,
    Tooltip
} from "antd";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest} from "@/util/http";
import {TEMPLATE_API} from "@/services/management";
import {FieldSelect, MachineSelect, NamespaceSelect} from "@/components";
import {POISON_API, SETTING_API} from "@/services/shoes";

const PoisonPage = () => {
    const [poisonForm] = Form.useForm();
    const [kcForm] = Form.useForm();

    useEffect(() => {

    }, []);

    const refreshAllPoisonPrice = () => {
        doPostRequest(POISON_API.REFRESH_ALL_PRICE, {}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    const refreshIncrementalPoisonPrice = () => {
        doPostRequest(POISON_API.REFRESH_INCREMENTAL_PRICE, {}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    return <>
        <Card title={"操作"}>
            <Form form={poisonForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item>
                        <Button type="primary" htmlType="submit" onClick={refreshAllPoisonPrice}>
                            全量刷新得物价格
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={refreshIncrementalPoisonPrice}>
                            增量刷新得物价格
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
    </>
}

export default PoisonPage;