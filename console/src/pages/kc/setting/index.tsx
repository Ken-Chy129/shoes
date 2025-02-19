import {
    Button, Card,
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
        doGetRequest(SETTING_API.QUERY_PRICE_SETTING, {}, {
            onSuccess: res => {
                console.log(res)
                settingForm.setFieldsValue(res.data);
            }
        });
    }, []);

    const updatePriceSetting = () => {
        const exchangeRate = settingForm.getFieldValue("exchangeRate");
        const freight = settingForm.getFieldValue("freight");
        const platformRate = settingForm.getFieldValue("platformRate");
        const minProfitRate = settingForm.getFieldValue("minProfitRate");
        const minProfit = settingForm.getFieldValue("minProfit");
        const priceType = settingForm.getFieldValue("priceType");
        console.log(priceType)
        doPostRequest(SETTING_API.UPDATE_PRICE_SETTING, {exchangeRate, freight, platformRate, minProfitRate, minProfit, priceType}, {
            onSuccess: _ => {
                message.success("修改成功").then(_ => {});
            }
        })
    }

    return <>
        <Card title={"基本配置"}>
            <Form layout={"vertical"} form={settingForm} style={{maxWidth: 600}}>
                <Form.Item name="exchangeRate" label="汇率">
                    <Input/>
                </Form.Item>
                <Form.Item name="freight" label="运费">
                    <Input/>
                </Form.Item>
                <Form.Item name="minProfit" label="最小利润">
                    <Input/>
                </Form.Item>
                <Form.Item name="priceType" label="价格类型">
                    <Select
                        options={[
                            {label: '普通价格', value: 'normal'},
                            {label: '闪电价格', value: 'flash'},
                            {label: '快速价格', value: 'fast'},
                        ]}
                    />
                </Form.Item>
                <Form.Item>
                    <Button type="primary" htmlType="submit" onClick={updatePriceSetting}>
                        修改
                    </Button>
                </Form.Item>
            </Form>
        </Card>
        <Card title={"商品爬取配置"} style={{marginTop: 10}}>

        </Card>
    </>
}

export default SettingPage;