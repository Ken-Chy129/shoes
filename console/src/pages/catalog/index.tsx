import {Button, Card, Empty, Form, Image, Input, Select, Space, Table, Tag, Typography, message} from 'antd';
import {PictureOutlined} from '@ant-design/icons';
import React, {useEffect, useState} from 'react';
import moment from 'moment';
import type {ProductCatalogItem} from '@/services/catalog';
import {PRODUCT_CATALOG_API, productCatalogDetailApi} from '@/services/catalog';
import {doGetRequest} from '@/util/http';
import CatalogEditDrawer from './CatalogEditDrawer';

const {Text} = Typography;

const SOURCE_LABELS: Record<string, string> = {
    kickscrew: 'KicksCrew',
    stockx: 'StockX',
    poison: '得物',
    manual: '人工填写',
};

const CatalogThumbnail = ({url, title}: {url?: string; title: string}) => {
    const [failed, setFailed] = useState(false);
    useEffect(() => setFailed(false), [url]);
    if (!url || failed) {
        return <div aria-label="暂无商品图片" style={{width: 56, height: 56, display: 'grid', placeItems: 'center'}}><PictureOutlined style={{fontSize: 24}} /></div>;
    }
    return <Image width={56} height={56} src={url} alt={title} preview={false} style={{objectFit: 'cover'}} onError={() => setFailed(true)} />;
};

const ProductCatalogPage = () => {
    const [form] = Form.useForm();
    const [products, setProducts] = useState<ProductCatalogItem[]>([]);
    const [filters, setFilters] = useState<Record<string, string>>({});
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(20);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);
    const [editing, setEditing] = useState<ProductCatalogItem>();

    const query = () => {
        setLoading(true);
        doGetRequest(PRODUCT_CATALOG_API.PAGE, {pageIndex, pageSize, ...filters}, {
            onSuccess: res => {
                setProducts(res.data || []);
                setTotal(res.total || 0);
            },
            onError: res => {
                setProducts([]);
                message.error(res.errorMsg || '商品资料加载失败');
            },
            onFinally: () => setLoading(false),
        });
    };

    useEffect(query, [pageIndex, pageSize, filters]);

    const openEditor = (record: ProductCatalogItem) => {
        setLoading(true);
        doGetRequest(productCatalogDetailApi(record.modelNo), {}, {
            onSuccess: res => setEditing(res.data),
            onError: res => message.error(res.errorMsg || '商品详情加载失败'),
            onFinally: () => setLoading(false),
        });
    };

    const columns = [
        {
            title: '主图', key: 'image', width: 88,
            render: (_: unknown, record: ProductCatalogItem) => <CatalogThumbnail url={record.imageUrls?.[0]} title={record.title} />,
        },
        {title: '货号', dataIndex: 'modelNo', key: 'modelNo', width: 160},
        {title: '品牌', dataIndex: 'brand', key: 'brand', width: 110, render: (value?: string) => value || '-'},
        {
            title: '商品', key: 'product', width: 340,
            render: (_: unknown, record: ProductCatalogItem) => (
                <Space direction="vertical" size={0}>
                    <Text strong>{record.title}</Text>
                    <Text type="secondary">{record.modelNo}{record.brand ? ` · ${record.brand}` : ''}</Text>
                </Space>
            ),
        },
        {
            title: '来源', dataIndex: 'source', key: 'source', width: 140,
            render: (source: string, record: ProductCatalogItem) => (
                <Space size={4} wrap><Tag>{SOURCE_LABELS[source] || source}</Tag>{record.manualOverride && <Tag color="blue">人工维护</Tag>}</Space>
            ),
        },
        {title: '图片数量', dataIndex: 'imageCount', key: 'imageCount', width: 100},
        {
            title: '更新时间', dataIndex: 'gmtModified', key: 'gmtModified', width: 180,
            render: (value?: string) => value ? moment(value).format('YYYY-MM-DD HH:mm') : '-',
        },
    ];

    const search = () => {
        setFilters(form.getFieldsValue());
        setPageIndex(1);
    };

    const reset = () => {
        form.resetFields();
        setFilters({});
        setPageIndex(1);
    };

    return (
        <Card title="商品资料库" extra={<Text type="secondary">按货号复用商品信息，人工修改优先</Text>}>
            <Form form={form} layout="inline" style={{marginBottom: 16, rowGap: 8}}>
                <Form.Item name="modelNo" label="货号"><Input allowClear placeholder="支持模糊搜索" /></Form.Item>
                <Form.Item name="brand" label="品牌"><Input allowClear placeholder="例如 Nike" /></Form.Item>
                <Form.Item name="source" label="来源">
                    <Select allowClear placeholder="全部来源" style={{width: 140}} options={Object.entries(SOURCE_LABELS).map(([value, label]) => ({value, label}))} />
                </Form.Item>
                <Form.Item><Space><Button type="primary" onClick={search}>查询</Button><Button onClick={reset}>重置</Button></Space></Form.Item>
            </Form>
            <Table
                rowKey="modelNo"
                columns={columns}
                dataSource={products}
                loading={loading}
                onRow={record => ({onClick: () => openEditor(record), style: {cursor: 'pointer'}})}
                scroll={{x: 1100}}
                locale={{emptyText: <Empty description="暂无商品资料；eBay 上架补全成功后会自动进入资料库" />}}
                pagination={{
                    current: pageIndex, pageSize, total, showSizeChanger: true,
                    showTotal: count => `共 ${count} 条`,
                    onChange: (page, size) => { setPageIndex(page); setPageSize(size); },
                }}
            />
            <CatalogEditDrawer
                open={Boolean(editing)}
                product={editing}
                onClose={() => setEditing(undefined)}
                onSaved={product => {
                    setEditing(undefined);
                    setProducts(current => current.map(item => item.modelNo === product.modelNo ? product : item));
                    query();
                }}
            />
        </Card>
    );
};

export default ProductCatalogPage;
