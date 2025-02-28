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
import {SETTING_API} from "@/services/shoes";

const SettingPage = () => {
    const [settingForm] = Form.useForm();

    useEffect(() => {
        doGetRequest(SETTING_API.POISON, {}, {
            onSuccess: res => {
                settingForm.setFieldsValue(res.data);
            }
        });
    }, []);

    const updatePriceSetting = () => {
        const priceType = settingForm.getFieldValue("priceType");
        const apiMode = settingForm.getFieldValue("apiMode");
        doPostRequest(SETTING_API.POISON, {priceType, apiMode}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    return <>
        <Card title={"基本配置"}>
            <Form form={settingForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="priceType" label="价格类型">
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            options={
                                [
                                    {label: '普通发货', value: 0},
                                    {label: '闪电发货', value: 1},
                                    {label: '极速发货', value: 2},
                                    {label: '品牌直发', value: 3}
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item name="apiMode" label="查价模式" style={{marginLeft: 20}}>
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
                    <Form.Item style={{marginLeft: 50}}>
                        <Button type="primary" htmlType="submit" onClick={updatePriceSetting}>
                            修改
                        </Button>
                    </Form.Item>
                </div>
            </Form>
        </Card>
    </>
}

export default SettingPage;