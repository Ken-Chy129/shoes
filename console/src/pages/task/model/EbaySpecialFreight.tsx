import {Button, Form, Input, InputNumber, message, Modal, Popconfirm, Space, Table, Typography, Upload} from "antd";
import {DownloadOutlined, PlusOutlined, UploadOutlined} from "@ant-design/icons";
import React, {useEffect, useState} from "react";
import {request} from "@@/exports";
import {doDeleteRequest, doGetRequest, doUploadRequestWithParams} from "@/util/http";
import {TASK_API} from "@/services/task";

interface SpecialFreightRecord {
    id: number;
    target: string;
    euSize: string;
    freight: number;
    gmtModified?: string;
}

/** eBay盈利改价的特殊运费：填AG运费，非AG链接按×1.2；每轮改价开始时读取。 */
const EbaySpecialFreight = () => {
    const [dataSource, setDataSource] = useState<SpecialFreightRecord[]>([]);
    const [loading, setLoading] = useState(false);
    const [keyword, setKeyword] = useState('');
    const [editing, setEditing] = useState<Partial<SpecialFreightRecord> | null>(null);
    const [saving, setSaving] = useState(false);
    const [form] = Form.useForm();

    const queryList = () => {
        setLoading(true);
        doGetRequest(TASK_API.EBAY_SPECIAL_FREIGHT_LIST, {}, {
            onSuccess: res => setDataSource(res.data || []),
            onError: res => message.error(res.errorMsg || '查询失败'),
            onFinally: () => setLoading(false),
        });
    };

    useEffect(queryList, []);

    const openEdit = (record?: SpecialFreightRecord) => {
        setEditing(record || {});
        form.setFieldsValue({target: record?.target, size: record?.euSize, freight: record?.freight});
    };

    const handleSave = () => form.validateFields().then(values => {
        setSaving(true);
        const params: any = {target: values.target.trim(), freight: values.freight};
        if (values.size?.trim()) params.size = values.size.trim();
        request(TASK_API.EBAY_SPECIAL_FREIGHT_SAVE, {method: 'POST', params})
            .then((res: any) => {
                if (!res.success) {
                    message.error(res.errorMsg || '保存失败');
                    return;
                }
                message.success('已保存');
                setEditing(null);
                queryList();
            })
            .catch(() => message.error('保存失败'))
            .finally(() => setSaving(false));
    });

    const handleDelete = (record: SpecialFreightRecord) => doDeleteRequest(
        TASK_API.EBAY_SPECIAL_FREIGHT_DELETE, {id: record.id}, {
            onSuccess: () => { message.success('删除成功'); queryList(); },
            onError: res => message.error(res.errorMsg || '删除失败'),
        });

    const handleImport = (file: any) => {
        doUploadRequestWithParams(TASK_API.EBAY_SPECIAL_FREIGHT_IMPORT, file, {}, {
            onSuccess: (res: any) => {
                if (!res.success) {
                    message.error(res.errorMsg || '导入失败');
                    return;
                }
                message.success(`成功导入 ${res.data} 条`);
                queryList();
            },
            onError: () => message.error('导入失败'),
        });
        return false;
    };

    const filtered = keyword.trim()
        ? dataSource.filter(r => r.target.toUpperCase().includes(keyword.trim().toUpperCase()))
        : dataSource;

    const columns = [
        {title: '货号或物品编号', dataIndex: 'target', key: 'target', width: 220},
        {title: '尺码', dataIndex: 'euSize', key: 'euSize', width: 100, render: (v: string) => v || '全尺码'},
        {title: 'AG运费', dataIndex: 'freight', key: 'freight', width: 110, render: (v: number) => `¥${v}`},
        {
            title: '非AG运费', key: 'nonAg', width: 110,
            render: (_: any, r: SpecialFreightRecord) => `¥${(Number(r.freight) * 1.2).toFixed(2)}`,
        },
        {title: '更新时间', dataIndex: 'gmtModified', key: 'gmtModified', width: 180},
        {
            title: '操作', key: 'action', width: 120,
            render: (_: any, record: SpecialFreightRecord) => <Space size={0}>
                <Button type="link" size="small" onClick={() => openEdit(record)}>编辑</Button>
                <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record)} okText="确定" cancelText="取消">
                    <Button type="link" size="small" danger>删除</Button>
                </Popconfirm>
            </Space>,
        },
    ];

    return <>
        <Typography.Paragraph type="secondary">
            eBay盈利改价每轮开始时读取这里的运费，命中的尺码不再用任务里的尺码段运费。填AG运费，非AG链接自动×1.2。
            匹配优先级：物品编号+尺码 &gt; 物品编号 &gt; 货号+尺码 &gt; 货号。
        </Typography.Paragraph>
        <div style={{display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'space-between', marginBottom: 16}}>
            <Input.Search placeholder="搜索货号或物品编号" allowClear style={{width: 240}}
                          onSearch={setKeyword}/>
            <Space wrap>
                <Button href={TASK_API.EBAY_SPECIAL_FREIGHT_TEMPLATE} icon={<DownloadOutlined/>}>下载模板</Button>
                <Upload accept=".xlsx,.xls" maxCount={1} beforeUpload={handleImport} showUploadList={false}>
                    <Button icon={<UploadOutlined/>}>导入Excel</Button>
                </Upload>
                <Button type="primary" icon={<PlusOutlined/>} onClick={() => openEdit()}>添加</Button>
            </Space>
        </div>
        <Table columns={columns} dataSource={filtered} loading={loading} rowKey="id" size="middle"
               pagination={{showSizeChanger: true, showTotal: t => `共 ${t} 条`}}/>
        <Modal title={editing?.id ? '编辑特殊运费' : '添加特殊运费'} open={editing !== null}
               onCancel={() => setEditing(null)} onOk={handleSave} confirmLoading={saving}
               okText="保存" cancelText="取消" destroyOnClose>
            <Form form={form} layout="vertical" preserve={false}>
                <Form.Item name="target" label="货号或物品编号"
                           rules={[{required: true, whitespace: true, message: '请输入货号或物品编号'}]}
                           extra="可粘贴商品链接；同一货号/编号+尺码再次保存会覆盖原值">
                    <Input/>
                </Form.Item>
                <Form.Item name="size" label="尺码(EU)" extra="不填表示全尺码">
                    <Input/>
                </Form.Item>
                <Form.Item name="freight" label="AG运费"
                           rules={[
                               {required: true, message: '请输入运费'},
                               {type: 'number', min: 0, max: 100000, message: '请输入0到100000之间的数字'},
                           ]}>
                    <InputNumber min={0} max={100000} precision={2} addonAfter="元" style={{width: 200}}/>
                </Form.Item>
            </Form>
        </Modal>
    </>;
};

export default EbaySpecialFreight;
