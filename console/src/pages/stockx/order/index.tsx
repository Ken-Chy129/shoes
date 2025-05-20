import {
    Button, Card,
    Form,
    Input, message,
    Table,
} from "antd";
import React, {useEffect, useState} from "react";
import {doPostRequest} from "@/util/http";
import {ORDER_API} from "@/services/stockx";

const SettingPage = () => {
    const [conditionForm] = Form.useForm();

    const [items, setItems] = useState<[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    useEffect(() => {

    }, []);

    const columns = [
        {
            title: '名称',
            dataIndex: 'name',
            key: 'name',
            width: '20%'
        },
        {
            title: '货号',
            dataIndex: 'total',
            key: 'total',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '品牌',
            dataIndex: 'crawlCnt',
            key: 'crawlCnt',
            width: '15%', // 设置列宽为30%
        },
        {
            title: '发行时间',
            dataIndex: 'needCrawlText',
            key: 'needCrawlText',
            width: '20%', // 设置列宽为30%
        },
        {
            title: '操作',
            key: 'action',
            render: (text: string, brandSetting: { name: string, needCrawl: boolean }) => (
                <span>
                  <Button onClick={() => {}}>
                    {brandSetting.needCrawl ? '暂停爬取' : '开启爬取'}
                  </Button>
                  <Button style={{marginLeft: 20}} onClick={() => {
                  }}>
                    修改爬取数量
                  </Button>
                </span>
            ),
            width: '30%', // 设置列宽为30%
        }
    ];

    const downloadOrders = () => {
        window.open('http://localhost:8080/order/kc/excel');
    }

    const extendAllItems = () => {
        doPostRequest(ORDER_API.EXTEND_ALL, {}, {
            onSuccess: _ => message.success("开始延期").then()
        })
    }

    return <>
        <Card title={"商品信息"} style={{marginTop: 10}}>
            <Form form={conditionForm}
                  style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
                <div style={{display: "flex"}}>
                    <Form.Item name="name" label="品牌名称">
                        <Input/>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={downloadOrders}>
                            订单导出
                        </Button>
                    </Form.Item>
                    <Form.Item style={{marginLeft: 30}}>
                        <Button type="primary" htmlType="submit" onClick={extendAllItems}>
                            批量订单延期
                        </Button>
                    </Form.Item>
                </div>
            </Form>
            <Table
                columns={columns}
                dataSource={items}
                pagination={{
                    current: pageIndex,
                    pageSize: pageSize,
                    total: total,
                    showSizeChanger: true,
                    onChange: (current, pageSize) => {
                        setPageIndex(current);
                        setPageSize(pageSize);
                    }
                }}
                rowKey="id"
            />
        </Card>
    </>
}

export default SettingPage;