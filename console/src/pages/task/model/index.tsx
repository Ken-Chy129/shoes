import {
    Button, Form, Input, message, Modal, Popconfirm, Select, Space, Table, Tag, Upload,
} from "antd";
import {PlusOutlined, UploadOutlined, DownloadOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest, doUploadRequestWithParams} from "@/util/http";
import {SETTING_API} from "@/services/shoes";

interface SpecialModelRecord {
    modelNo: string;
    euSize: string;
    category: string;
}

const CATEGORY_OPTIONS = [
    {label: '全部', value: ''},
    {label: '必爬', value: 'mustCrawl'},
    {label: '禁爬', value: 'forbidden'},
    {label: '不比价', value: 'notCompare'},
];

const CATEGORY_LABELS: Record<string, { text: string; color: string }> = {
    mustCrawl: {text: '必爬', color: 'blue'},
    forbidden: {text: '禁爬', color: 'red'},
    notCompare: {text: '不比价', color: 'orange'},
};

const ModelPage = () => {
    const [dataSource, setDataSource] = useState<SpecialModelRecord[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(20);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);

    const [conditionForm] = Form.useForm();
    const [filterCategory, setFilterCategory] = useState('');
    const [filterModelNo, setFilterModelNo] = useState('');

    const [addModalVisible, setAddModalVisible] = useState(false);
    const [addCategory, setAddCategory] = useState('mustCrawl');
    const [addModelNos, setAddModelNos] = useState('');
    const [adding, setAdding] = useState(false);


    useEffect(() => {
        queryList();
    }, [pageIndex, pageSize, filterCategory, filterModelNo]);

    const queryList = () => {
        setLoading(true);
        const params: any = {pageIndex, pageSize};
        if (filterCategory) params.category = filterCategory;
        if (filterModelNo) params.modelNo = filterModelNo;
        doGetRequest(SETTING_API.SPECIAL_MODEL_PAGE, params, {
            onSuccess: res => {
                setDataSource(res.data || []);
                setTotal(res.total || 0);
            },
            onFinally: () => setLoading(false),
        });
    };

    const handleQuery = () => {
        setFilterCategory(conditionForm.getFieldValue('category') || '');
        setFilterModelNo(conditionForm.getFieldValue('modelNo') || '');
        setPageIndex(1);
    };

    const handleReset = () => {
        conditionForm.resetFields();
        setFilterCategory('');
        setFilterModelNo('');
        setPageIndex(1);
    };

    const handleDelete = (record: SpecialModelRecord) => {
        const params: any = {category: record.category, modelNo: record.modelNo};
        if (record.euSize) params.euSize = record.euSize;
        doDeleteRequest(SETTING_API.DELETE_SPECIAL_MODEL, params, {
            onSuccess: () => {
                message.success('删除成功');
                queryList();
            },
        });
    };

    const handleAdd = () => {
        if (!addModelNos.trim()) {
            message.warning('请输入货号');
            return;
        }
        setAdding(true);
        doPostRequest(SETTING_API.ADD_SPECIAL_MODEL, {category: addCategory, modelNos: addModelNos}, {
            onSuccess: res => {
                message.success(`成功添加 ${res.data} 条`);
                setAddModalVisible(false);
                setAddModelNos('');
                queryList();
            },
            onFinally: () => setAdding(false),
        });
    };

    const handleImport = (file: any) => {
        doUploadRequestWithParams(SETTING_API.IMPORT_SPECIAL_MODEL_EXCEL, file, {}, {
            onSuccess: res => {
                message.success(`成功导入 ${res.data} 条`);
                queryList();
            },
            onError: () => message.error('导入失败'),
        });
        return false;
    };

    const columns = [
        {
            title: '货号', dataIndex: 'modelNo', key: 'modelNo', width: 200,
        },
        {
            title: '尺码', dataIndex: 'euSize', key: 'euSize', width: 100,
            render: (v: string) => v || '-',
        },
        {
            title: '类型', dataIndex: 'category', key: 'category', width: 100,
            render: (category: string) => {
                const info = CATEGORY_LABELS[category];
                return info ? <Tag color={info.color}>{info.text}</Tag> : category;
            },
        },
        {
            title: '操作', key: 'action', width: 80,
            render: (_: any, record: SpecialModelRecord) => (
                <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record)} okText="确定" cancelText="取消">
                    <Button type="link" size="small" danger>删除</Button>
                </Popconfirm>
            ),
        },
    ];

    return <>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16}}>
            <Form form={conditionForm} layout="inline" style={{flex: 1, flexWrap: 'wrap', gap: 8}}>
                <Form.Item name="category" label="类型">
                    <Select style={{width: 120}} placeholder="全部" allowClear options={CATEGORY_OPTIONS.filter(o => o.value !== '')}/>
                </Form.Item>
                <Form.Item name="modelNo" label="货号">
                    <Input placeholder="搜索货号" style={{width: 180}} allowClear/>
                </Form.Item>
                <Form.Item>
                    <Space>
                        <Button type="primary" onClick={handleQuery}>查询</Button>
                        <Button onClick={handleReset}>重置</Button>
                    </Space>
                </Form.Item>
            </Form>
            <Space>
                <Button href={SETTING_API.SPECIAL_MODEL_TEMPLATE} icon={<DownloadOutlined/>}>
                    下载模板
                </Button>
                <Upload accept=".xlsx,.xls" maxCount={1} beforeUpload={handleImport} showUploadList={false}>
                    <Button icon={<UploadOutlined/>}>导入Excel</Button>
                </Upload>
                <Button type="primary" icon={<PlusOutlined/>} onClick={() => setAddModalVisible(true)}>
                    添加
                </Button>
            </Space>
        </div>

        <Table
            columns={columns}
            dataSource={dataSource}
            loading={loading}
            rowKey={(r) => `${r.category}:${r.modelNo}:${r.euSize}`}
            size="middle"
            pagination={{
                current: pageIndex, pageSize, total, showSizeChanger: true,
                showTotal: t => `共 ${t} 条`,
                onChange: (c, s) => { setPageIndex(c); setPageSize(s); },
            }}
        />

        {/* 添加 Modal */}
        <Modal
            title="添加货号" open={addModalVisible} onCancel={() => setAddModalVisible(false)}
            onOk={handleAdd} confirmLoading={adding} okText="添加" cancelText="取消" width={480}
        >
            <div style={{marginBottom: 16}}>
                <span style={{marginRight: 8}}>类型：</span>
                <Select value={addCategory} onChange={setAddCategory} style={{width: 120}}
                    options={CATEGORY_OPTIONS.filter(o => o.value !== '')}/>
            </div>
            <Input.TextArea
                rows={10} value={addModelNos} onChange={e => setAddModelNos(e.target.value)}
                placeholder={"每行一个货号，支持 货号:尺码 格式\n例如：\nDV0833-105\nFD8775-100:42"}
            />
        </Modal>

    </>;
};

export default ModelPage;
