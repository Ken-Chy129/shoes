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
import {KC_API} from "@/services/kc";

const SettingPage = () => {
    const [settingForm] = Form.useForm();

    useEffect(() => {
        doGetRequest(KC_API.KC, {}, {
            onSuccess: res => {
                console.log(res)
                settingForm.setFieldsValue(res.data);
            }
        });
    }, []);


    return <>
        <Card >
            <Form form={settingForm} style={{maxWidth: 600}}>
                <Form.Item name="exchangeRate" label="汇率">
                    <Input/>
                </Form.Item>
                <Form.Item name="freight" label="运费">
                    <Input/>
                </Form.Item>
                <Form.Item name="platformRate" label="平台抽成费率">
                    <Input/>
                </Form.Item>
                <Form.Item name="minProfitRate" label="最小利润率">
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
                    <Button type="primary" htmlType="submit" onClick={() => {}}>
                        修改
                    </Button>
                </Form.Item>
            </Form>
        </Card>

    </>
}

export default SettingPage;