import {
    Button, Card, DatePicker,
    Form,
    Input, List,
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
import {STOCKX_API} from "@/services/stockx";
import {STOCKX_DOWNLOAD_API} from "@/services/file";


const BrandPage = () => {
    const [conditionForm] = Form.useForm();

    const [items, setItems] = useState<[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    useEffect(() => {
        searchItems();
    }, [pageIndex, pageSize]);


    const searchItems = () => {
        const query = conditionForm.getFieldValue("query");
        doGetRequest(STOCKX_API.SEARCH, {query, pageIndex, pageSize}, {
            onSuccess: res => {
                setItems(res.data);
                setTotal(res.total);
            }
        });
    }

    const downloadItems = () => {
        const query = conditionForm.getFieldValue("query");
        doGetRequest(STOCKX_DOWNLOAD_API.SEARCH, {query}, {
            onSuccess: res => {
                message.success("导出成功")
            }
        })
    }

    const columns = [
        {
            title: '名称',
            dataIndex: 'title',
            key: 'title',
            width: '20%'
        },
        {
            title: '货号',
            dataIndex: 'modelNo',
            key: 'modelNo',
            width: '20%', // 设置列宽为30%
        },
        {
            title: 'us码',
            dataIndex: 'usmSize',
            key: 'usmSize',
            width: '10%', // 设置列宽为30%
        },
        {
            title: 'eu码',
            dataIndex: 'euSize',
            key: 'euSize',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '绿叉价格',
            dataIndex: 'price',
            key: 'price',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '绿叉求购价',
            dataIndex: 'purchasePrice',
            key: 'purchasePrice',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '绿叉72小时销量',
            dataIndex: 'last72HoursSales',
            key: 'last72HoursSales',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '得物价格',
            dataIndex: 'poisonPrice',
            key: 'poisonPrice',
            width: '10%', // 设置列宽为30%
        },
    ];

    return <>
        <Card title={"商品搜索"} style={{marginTop: 10}}>
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="query" label="关键词搜索">
                        <Input/>
                    </Form.Item>
                    <Form.Item name="sortType" label="排序" style={{marginLeft: 20}}>
                        <Select
                            style={{width: 160}}
                            placeholder="请选择字段"
                            allowClear
                            optionFilterProp="label"
                            defaultValue={'featured'}
                            options={
                                [
                                    {label: '精选', value: 'featured'},
                                    {label: 'Top Selling', value: 'most-active'},
                                    {label: 'Price: Low to High', value: 'lowest_ask'},
                                    {label: '出价: 从高到低', value: 'highest_bid'},
                                    {label: 'Recent High Bids', value: 'recent_bids'},
                                    {label: 'Recent Price Drops', value: 'recent_asks'},
                                    {label: 'Total Sold: High to Low', value: 'deadstock_sold'},
                                    {label: '发布日期', value: 'release_date'},
                                    {label: 'Price Premium: High to Low', value: 'price_premium'},
                                    {label: 'Last Sale: High to Low', value: 'last_sale'},
                                ]
                            }
                        />
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={searchItems}>
                            查询
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
                            重置
                        </Button>
                    </Form.Item>
                </div>
                <div style={{display: "flex"}}>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button onClick={() => downloadItems()}>
                            导出
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Table
                columns={columns}
                dataSource={items}
                pagination={false}
                rowKey="id"
            />
        </Card>
    </>
}

export default BrandPage;