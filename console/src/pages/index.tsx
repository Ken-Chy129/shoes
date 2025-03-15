import {Button, Card, Form, Input, message, Select} from "antd";
import React, {useEffect} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

const SettingPage = () => {
    const [poisonForm] = Form.useForm();
    const [kcForm] = Form.useForm();

    useEffect(() => {
        doGetRequest(SETTING_API.POISON, {}, {
            onSuccess: res => {
                poisonForm.setFieldsValue(res.data);
            }
        });
        doGetRequest(SETTING_API.KC, {}, {
            onSuccess: res => {
                kcForm.setFieldsValue(res.data);
            }
        });
    }, []);

    const updatePoisonSetting = () => {
        const apiMode = poisonForm.getFieldValue("apiMode");
        const maxPrice = poisonForm.getFieldValue("maxPrice");
        doPostRequest(SETTING_API.POISON, {apiMode, maxPrice}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    const updateKcSetting = () => {
        const exchangeRate = kcForm.getFieldValue("exchangeRate");
        const freight = kcForm.getFieldValue("freight");
        const minProfit = kcForm.getFieldValue("minProfit");
        doPostRequest(SETTING_API.KC, {exchangeRate, freight, minProfit}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    const authorize = () => {
        doGetRequest(SETTING_API.AUTHORIZE_URL, {}, {
            onSuccess: res => {
                window.open(res.data, '_blank');
            }
        })
    }

    return <>
        <Card title={"得物配置"}>
            <Form form={poisonForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="apiMode" label="查价模式">
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            options={
                                [
                                    {label: '实时查询', value: 0},
                                    {label: '缓存查询', value: 1},
                                    {label: '综合模式', value: 2}
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item name="maxPrice" label="最大价格限制" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updatePoisonSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"kc配置"}>
            <Form form={kcForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="exchangeRate" label="汇率">
                        <Input />
                    </Form.Item>
                    <Form.Item name="freight" label="运费" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item name="minProfit" label="最小利润" style={{marginLeft: 20}}>
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateKcSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"stockx配置"}>
            <Form form={kcForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item>
                        <Button type="primary" htmlType="submit" onClick={authorize}>
                            初始化认证Code
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
        <br/>
        <Card title={"接口配置"}>
            <Form form={kcForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="exchangeRate" label="汇率">
                        <Input />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updateKcSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
    </>
}

export default SettingPage;