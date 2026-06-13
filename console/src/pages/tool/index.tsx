import {Button, Card, Form, Input, message, Modal, Select, Space, Table} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest} from "@/util/http";
import {PRICE_API, SIZE_CHART_API} from "@/services/shoes";

const ToolPage = () => {
    const [priceForm] = Form.useForm();
    const [sizeForm] = Form.useForm();

    const [priceList, setPriceList] = useState<any[]>([]);
    const [priceModalVisible, setPriceModalVisible] = useState(false);
    const [priceModelNo, setPriceModelNo] = useState('');

    const [sizeList, setSizeList] = useState<any[]>([]);
    const [sizeModalVisible, setSizeModalVisible] = useState(false);
    const [sizeLoading, setSizeLoading] = useState(false);

    const [brandOptions, setBrandOptions] = useState<{label: string, value: string}[]>([]);

    useEffect(() => {
        doGetRequest(SIZE_CHART_API.BRANDS, {}, {
            onSuccess: res => {
                setBrandOptions((res.data || []).map((b: string) => ({label: b, value: b})));
            }
        });
    }, []);

    const queryPrice = () => {
        const modelNo = priceForm.getFieldValue("modelNo");
        if (!modelNo?.trim()) {
            message.warning("请输入货号");
            return;
        }
        doGetRequest(PRICE_API.QUERY_BY_MODEL, {modelNo, mode: "dbFirst"}, {
            onSuccess: res => {
                setPriceList(res.data || []);
                setPriceModelNo(modelNo);
                setPriceModalVisible(true);
            },
            onError: () => setPriceList([]),
        });
    };

    const querySize = () => {
        const brand = sizeForm.getFieldValue("brand");
        if (!brand) {
            message.warning("请选择品牌");
            return;
        }
        const gender = sizeForm.getFieldValue("gender") || '';
        setSizeLoading(true);
        doGetRequest(SIZE_CHART_API.LIST, {brand, gender, pageIndex: 1, pageSize: 200}, {
            onSuccess: res => {
                setSizeList(res.data || []);
                setSizeModalVisible(true);
            },
            onError: () => setSizeList([]),
            onFinally: () => setSizeLoading(false),
        });
    };

    const priceColumns = [
        {title: '尺码', dataIndex: 'size', key: 'size'},
        {title: '得物价格', dataIndex: 'poisonPrice', key: 'poisonPrice'},
        {title: 'KC价格', dataIndex: 'kcPrice', key: 'kcPrice'},
        {title: 'KC盈利(-1)', dataIndex: 'kcEarn', key: 'kcEarn'},
        {title: '绿叉盈利(-1)', dataIndex: 'stockxEarn', key: 'stockxEarn'},
    ];

    const sizeColumns = [
        {title: '品牌', dataIndex: 'brand', key: 'brand', width: 100},
        {title: '性别', dataIndex: 'gender', key: 'gender', width: 80},
        {title: '欧码', dataIndex: 'euSize', key: 'euSize', width: 80},
        {title: '美码', dataIndex: 'usSize', key: 'usSize', width: 80},
        {title: '男美码', dataIndex: 'menUSSize', key: 'menUSSize', width: 80},
        {title: '女美码', dataIndex: 'womenUSSize', key: 'womenUSSize', width: 80},
        {title: '英码', dataIndex: 'ukSize', key: 'ukSize', width: 80},
        {title: 'CM', dataIndex: 'cmSize', key: 'cmSize', width: 80},
    ];

    return <>
        <Card title="得物价格查询">
            <Space>
                <Form form={priceForm} layout="inline">
                    <Form.Item name="modelNo" label="货号">
                        <Input placeholder="请输入货号" onPressEnter={queryPrice}/>
                    </Form.Item>
                </Form>
                <Button type="primary" onClick={queryPrice}>查询</Button>
            </Space>
        </Card>

        <Card title="尺码查询" style={{marginTop: 16}}>
            <Space>
                <Form form={sizeForm} layout="inline">
                    <Form.Item name="brand" label="品牌">
                        <Select
                            placeholder="请选择品牌"
                            allowClear
                            showSearch
                            style={{width: 150}}
                            options={brandOptions}
                        />
                    </Form.Item>
                    <Form.Item name="gender" label="性别">
                        <Select
                            placeholder="请选择性别"
                            allowClear
                            style={{width: 120}}
                            options={[
                                {label: '男', value: 'men'},
                                {label: '女', value: 'women'},
                                {label: '通用', value: 'unisex'},
                            ]}
                        />
                    </Form.Item>
                </Form>
                <Button type="primary" onClick={querySize} loading={sizeLoading}>查询</Button>
            </Space>
        </Card>

        <Modal
            title={`价格查询 - ${priceModelNo}`}
            open={priceModalVisible}
            onCancel={() => setPriceModalVisible(false)}
            footer={null}
            width={700}
        >
            <Table
                columns={priceColumns}
                dataSource={priceList}
                rowKey="size"
                size="small"
                pagination={false}
            />
        </Modal>

        <Modal
            title="尺码查询结果"
            open={sizeModalVisible}
            onCancel={() => setSizeModalVisible(false)}
            footer={null}
            width={800}
        >
            <Table
                columns={sizeColumns}
                dataSource={sizeList}
                rowKey={(r) => `${r.brand}-${r.gender}-${r.euSize}-${r.usSize}`}
                size="small"
                pagination={{pageSize: 20, showTotal: t => `共 ${t} 条`}}
                scroll={{x: 700}}
            />
        </Modal>
    </>;
};

export default ToolPage;
