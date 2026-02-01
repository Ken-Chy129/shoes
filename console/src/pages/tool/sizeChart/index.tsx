import {
    Button,
    Card,
    Form,
    Input,
    message,
    Modal,
    Popconfirm,
    Select,
    Space,
    Table,
} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest, doPostRequest} from "@/util/http";
import {SIZE_CHART_API} from "@/services/shoes";

interface SizeChartItem {
    brand: string;
    gender: string;
    euSize: string;
    usSize: string;
    menUSSize?: string;
    womenUSSize?: string;
    ukSize?: string;
    cmSize?: string;
    dunkBrand?: string;
    stockxBrand?: string;
    // 用于编辑时传递原始主键
    oldBrand?: string;
    oldGender?: string;
    oldEuSize?: string;
    oldUsSize?: string;
}

const SizeChartPage = () => {
    const [queryForm] = Form.useForm();
    const [editForm] = Form.useForm();

    const [dataList, setDataList] = useState<SizeChartItem[]>([]);
    const [loading, setLoading] = useState(false);
    const [total, setTotal] = useState(0);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);

    const [modalVisible, setModalVisible] = useState(false);
    const [modalType, setModalType] = useState<'add' | 'edit'>('add');
    const [currentRecord, setCurrentRecord] = useState<SizeChartItem | null>(null);
    const [brandOptions, setBrandOptions] = useState<{label: string, value: string}[]>([]);

    useEffect(() => {
        fetchBrands();
    }, []);

    useEffect(() => {
        fetchData();
    }, [pageIndex, pageSize]);

    const fetchBrands = () => {
        doGetRequest(SIZE_CHART_API.BRANDS, {}, {
            onSuccess: res => {
                const options = (res.data || []).map((brand: string) => ({
                    label: brand,
                    value: brand
                }));
                setBrandOptions(options);
            }
        });
    };

    const fetchData = () => {
        const brand = queryForm.getFieldValue("brand") || '';
        const gender = queryForm.getFieldValue("gender") || '';
        setLoading(true);
        doGetRequest(SIZE_CHART_API.LIST, {brand, gender, pageIndex, pageSize}, {
            onSuccess: res => {
                setDataList(res.data || []);
                setTotal(res.total || 0);
            },
            onError: _ => {
                setDataList([]);
                setTotal(0);
            },
            onFinally: () => setLoading(false)
        });
    };

    const handleQuery = () => {
        setPageIndex(1);
        fetchData();
    };

    const handleReset = () => {
        queryForm.resetFields();
        setPageIndex(1);
        fetchData();
    };

    const handleAdd = () => {
        setModalType('add');
        setCurrentRecord(null);
        editForm.resetFields();
        setModalVisible(true);
    };

    const handleEdit = (record: SizeChartItem) => {
        setModalType('edit');
        setCurrentRecord(record);
        editForm.setFieldsValue(record);
        setModalVisible(true);
    };

    const handleDelete = (record: SizeChartItem) => {
        doPostRequest(SIZE_CHART_API.DELETE, record, {
            onSuccess: _ => {
                message.success("删除成功");
                fetchData();
            },
            onError: res => {
                message.error(res.errorMsg || "删除失败");
            }
        });
    };

    const handleModalOk = () => {
        editForm.validateFields().then(values => {
            const api = modalType === 'add' ? SIZE_CHART_API.ADD : SIZE_CHART_API.UPDATE;
            const data = modalType === 'edit' && currentRecord ? {
                ...values,
                oldBrand: currentRecord.brand,
                oldGender: currentRecord.gender,
                oldEuSize: currentRecord.euSize,
                oldUsSize: currentRecord.usSize,
            } : values;
            doPostRequest(api, data, {
                onSuccess: _ => {
                    message.success(modalType === 'add' ? "新增成功" : "修改成功");
                    setModalVisible(false);
                    fetchData();
                },
                onError: res => {
                    message.error(res.errorMsg || (modalType === 'add' ? "新增失败" : "修改失败"));
                }
            });
        });
    };

    const columns = [
        {
            title: '品牌',
            dataIndex: 'brand',
            key: 'brand',
            width: 100,
        },
        {
            title: '性别',
            dataIndex: 'gender',
            key: 'gender',
            width: 80,
        },
        {
            title: '欧码',
            dataIndex: 'euSize',
            key: 'euSize',
            width: 80,
        },
        {
            title: '美码',
            dataIndex: 'usSize',
            key: 'usSize',
            width: 80,
        },
        {
            title: '男美码',
            dataIndex: 'menUSSize',
            key: 'menUSSize',
            width: 80,
        },
        {
            title: '女美码',
            dataIndex: 'womenUSSize',
            key: 'womenUSSize',
            width: 80,
        },
        {
            title: '英码',
            dataIndex: 'ukSize',
            key: 'ukSize',
            width: 80,
        },
        {
            title: 'CM',
            dataIndex: 'cmSize',
            key: 'cmSize',
            width: 80,
        },
        {
            title: 'Dunk品牌',
            dataIndex: 'dunkBrand',
            key: 'dunkBrand',
            width: 100,
        },
        {
            title: 'StockX品牌',
            dataIndex: 'stockxBrand',
            key: 'stockxBrand',
            width: 100,
        },
        {
            title: '操作',
            key: 'action',
            width: 150,
            render: (_: any, record: SizeChartItem) => (
                <Space>
                    <Button type="link" onClick={() => handleEdit(record)}>
                        编辑
                    </Button>
                    <Popconfirm
                        title="确定要删除这条记录吗？"
                        onConfirm={() => handleDelete(record)}
                        okText="确定"
                        cancelText="取消"
                    >
                        <Button type="link" danger>
                            删除
                        </Button>
                    </Popconfirm>
                </Space>
            ),
        }
    ];

    return (
        <>
            <Card title="尺码表管理">
                <Form form={queryForm} layout="inline" style={{marginBottom: 16}}>
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
                    <Form.Item>
                        <Space>
                            <Button type="primary" onClick={handleQuery}>
                                查询
                            </Button>
                            <Button onClick={handleReset}>
                                重置
                            </Button>
                            <Button type="primary" onClick={handleAdd}>
                                新增
                            </Button>
                        </Space>
                    </Form.Item>
                </Form>
                <Table
                    columns={columns}
                    dataSource={dataList}
                    loading={loading}
                    rowKey={(record) => `${record.brand}-${record.gender}-${record.euSize}-${record.usSize}`}
                    pagination={{
                        current: pageIndex,
                        pageSize: pageSize,
                        total: total,
                        showSizeChanger: true,
                        showQuickJumper: true,
                        showTotal: (total) => `共 ${total} 条`,
                        onChange: (page, size) => {
                            setPageIndex(page);
                            setPageSize(size);
                        }
                    }}
                    scroll={{x: 1100}}
                />
            </Card>

            <Modal
                title={modalType === 'add' ? '新增尺码' : '编辑尺码'}
                open={modalVisible}
                onOk={handleModalOk}
                onCancel={() => setModalVisible(false)}
                width={600}
            >
                <Form form={editForm} labelCol={{span: 6}} wrapperCol={{span: 16}}>
                    <Form.Item
                        name="brand"
                        label="品牌"
                        rules={[{required: true, message: '请选择品牌'}]}
                    >
                        <Select
                            placeholder="请选择品牌"
                            showSearch
                            disabled={modalType === 'edit'}
                            options={brandOptions}
                        />
                    </Form.Item>
                    <Form.Item
                        name="gender"
                        label="性别"
                        rules={[{required: true, message: '请选择性别'}]}
                    >
                        <Select
                            placeholder="请选择性别"
                            disabled={modalType === 'edit'}
                            options={[
                                {label: '男', value: 'men'},
                                {label: '女', value: 'women'},
                                {label: '通用', value: 'unisex'},
                            ]}
                        />
                    </Form.Item>
                    <Form.Item
                        name="euSize"
                        label="欧码"
                        rules={[{required: true, message: '请输入欧码'}]}
                    >
                        <Input placeholder="请输入欧码"/>
                    </Form.Item>
                    <Form.Item
                        name="usSize"
                        label="美码"
                        rules={[{required: true, message: '请输入美码'}]}
                    >
                        <Input placeholder="请输入美码"/>
                    </Form.Item>
                    <Form.Item name="menUSSize" label="男美码">
                        <Input placeholder="请输入男美码"/>
                    </Form.Item>
                    <Form.Item name="womenUSSize" label="女美码">
                        <Input placeholder="请输入女美码"/>
                    </Form.Item>
                    <Form.Item name="ukSize" label="英码">
                        <Input placeholder="请输入英码"/>
                    </Form.Item>
                    <Form.Item name="cmSize" label="CM">
                        <Input placeholder="请输入CM"/>
                    </Form.Item>
                    <Form.Item name="dunkBrand" label="Dunk品牌">
                        <Input placeholder="请输入Dunk品牌"/>
                    </Form.Item>
                    <Form.Item name="stockxBrand" label="StockX品牌">
                        <Input placeholder="请输入StockX品牌"/>
                    </Form.Item>
                </Form>
            </Modal>
        </>
    );
};

export default SizeChartPage;
