import {
    Button, Card,
    Form,
    Input,
    message,
    Select,
    Table,
} from "antd";
import React, {useState} from "react";
import {doGetRequest} from "@/util/http";
import {PRICE_API} from "@/services/shoes";

const PricePage = () => {
    const [conditionForm] = Form.useForm();

    const [priceList, setPriceList] = useState<[]>([]);


    const queryPriceByModel = () => {
        const modelNo = conditionForm.getFieldValue("modelNo");
        const mode = conditionForm.getFieldValue("mode") ?? "dbFirst";
        doGetRequest(PRICE_API.QUERY_BY_MODEL, {modelNo, mode}, {
            onSuccess: res => {
                setPriceList(res.data);
                message.success("查询成功").then();
            },
            onError: _ => {
                setPriceList([]);
            }
        });
    }

    const columns = [
        {
            title: '尺码',
            dataIndex: 'size',
            key: 'size',
            width: '10%'
        },
        {
            title: '得物价格',
            dataIndex: 'poisonPrice',
            key: 'poisonPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: 'kc价格',
            dataIndex: 'kcPrice',
            key: 'kcPrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: 'kc盈利(-1)',
            dataIndex: 'kcEarn',
            key: 'kcEarn',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '绿叉盈利(-1)',
            dataIndex: 'stockxEarn',
            key: 'stockxEarn',
            width: '10%', // 设置列宽为30%
        },
    ];

    return <>
        <Card title={"价格查询"} style={{marginTop: 10}}>
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="modelNo" label="货号">
                        <Input/>
                    </Form.Item>
                    <Form.Item name="mode" label="查询模式" style={{marginLeft: 20}}>
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            defaultValue={"dbFirst"}
                            options={
                                [
                                    {label: '数据库查询', value: "db"},
                                    {label: '实时查询', value: "realTime"},
                                    {label: '数据库优先', value: "dbFirst"},
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={queryPriceByModel}>
                            查询
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
                            重置
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Table
                columns={columns}
                dataSource={priceList}
                rowKey="id"
            />
        </Card>

    </>
}

export default PricePage;