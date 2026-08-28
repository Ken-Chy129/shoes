import {Button, Drawer, Form, Image, Input, Space, Tag, Typography, message} from 'antd';
import {PictureOutlined} from '@ant-design/icons';
import React, {useEffect, useState} from 'react';
import type {ProductCatalogItem} from '@/services/catalog';
import {productCatalogDetailApi} from '@/services/catalog';
import {doPatchRequest} from '@/util/http';

const {Text} = Typography;

const splitImageUrls = (value?: string) => (value || '')
    .split(/[\r\n,;]+/)
    .map(url => url.trim())
    .filter(Boolean)
    .filter((url, index, urls) => urls.indexOf(url) === index)
    .slice(0, 20);

const ImagePreviewItem = ({url, index}: {url: string; index: number}) => {
    const [failed, setFailed] = useState(false);
    useEffect(() => setFailed(false), [url]);
    if (failed) {
        return (
            <div style={{width: 88, textAlign: 'center'}} title={url}>
                <div style={{width: 88, height: 88, display: 'grid', placeItems: 'center', border: '1px dashed #d9d9d9', borderRadius: 6, color: '#999'}}>
                    <PictureOutlined style={{fontSize: 24}} />
                </div>
                <Text type="danger" style={{fontSize: 12}}>第{index + 1}张加载失败</Text>
            </div>
        );
    }
    return <Image width={88} height={88} src={url} alt={`商品图片 ${index + 1}`} style={{objectFit: 'cover', borderRadius: 6}} onError={() => setFailed(true)} />;
};

const ImageUrlPreview = ({value}: {value?: string}) => {
    const urls = splitImageUrls(value);
    if (urls.length === 0) {
        return <Text type="secondary">输入链接后将在这里显示图片预览</Text>;
    }
    return (
        <div style={{marginTop: 8}}>
            <Image.PreviewGroup>
                <Space size={[8, 8]} wrap>
                    {urls.map((url, index) => <ImagePreviewItem key={`${url}-${index}`} url={url} index={index} />)}
                </Space>
            </Image.PreviewGroup>
            <div style={{marginTop: 8}}><Tag>{urls.length} 张预览</Tag><Text type="secondary">点击图片可放大；加载失败时请检查链接是否公开可访问</Text></div>
        </div>
    );
};

interface CatalogEditDrawerProps {
    open: boolean;
    product?: ProductCatalogItem;
    onClose: () => void;
    onSaved: (product: ProductCatalogItem) => void;
}

const CatalogEditDrawer = ({open, product, onClose, onSaved}: CatalogEditDrawerProps) => {
    const [form] = Form.useForm();
    const [saving, setSaving] = useState(false);
    const imageUrlsValue = Form.useWatch('imageUrls', form);

    useEffect(() => {
        if (!open || !product) return;
        form.setFieldsValue({
            ...product,
            imageUrls: (product.imageUrls || []).join('\n'),
        });
    }, [open, product, form]);

    const save = async () => {
        if (!product) return;
        let values;
        try {
            values = await form.validateFields();
        } catch (_) {
            return;
        }
        const imageUrls = values.imageUrls
            .split(/[\r\n,;]+/)
            .map((value: string) => value.trim())
            .filter(Boolean);
        setSaving(true);
        doPatchRequest(productCatalogDetailApi(product.modelNo), {...values, imageUrls}, {
            onSuccess: res => {
                message.success('商品资料已保存');
                onSaved(res.data);
            },
            onError: res => message.error(res.errorMsg || '保存失败'),
            onFinally: () => setSaving(false),
        });
    };

    return (
        <Drawer
            title="编辑商品资料"
            width="min(640px, 100vw)"
            open={open}
            onClose={onClose}
            destroyOnClose
            extra={<Space><Button onClick={onClose}>取消</Button><Button type="primary" loading={saving} onClick={save}>保存</Button></Space>}
        >
            <Form form={form} layout="vertical" preserve={false}>
                <Form.Item label="货号"><Input value={product?.modelNo} disabled /></Form.Item>
                <Form.Item name="title" label="标题" rules={[{required: true, whitespace: true, message: '请输入标题'}]}>
                    <Input maxLength={255} showCount />
                </Form.Item>
                <Form.Item name="brand" label="品牌"><Input maxLength={65} /></Form.Item>
                <Form.Item name="description" label="描述"><Input.TextArea rows={5} maxLength={16000} showCount /></Form.Item>
                <Space size="middle" wrap style={{display: 'flex'}}>
                    <Form.Item name="productType" label="商品类型"><Input maxLength={64} /></Form.Item>
                    <Form.Item name="modelName" label="型号（Model）"><Input maxLength={128} /></Form.Item>
                    <Form.Item name="productLine" label="产品线"><Input maxLength={128} /></Form.Item>
                    <Form.Item name="gender" label="性别"><Input maxLength={32} /></Form.Item>
                    <Form.Item name="color" label="颜色"><Input maxLength={128} /></Form.Item>
                </Space>
                <Form.Item
                    name="countryOfOrigin"
                    label="原产国"
                    extra="仅填写可靠的商品产地，不能根据发货仓库所在国家推断。"
                >
                    <Input maxLength={64} />
                </Form.Item>
                <Form.Item name="colorway" label="配色"><Input maxLength={255} /></Form.Item>
                <Form.Item name="upperMaterial" label="鞋面材质"><Input maxLength={128} /></Form.Item>
                <Form.Item
                    name="imageUrls"
                    label="图片链接"
                    extra="每行一个 http/https 链接，最多保存 20 张；eBay 上架时使用前 12 张。"
                    rules={[{required: true, whitespace: true, message: '请至少保留一张图片'}]}
                >
                    <Input.TextArea rows={8} placeholder="https://example.com/image-1.jpg" />
                </Form.Item>
                <ImageUrlPreview value={imageUrlsValue} />
            </Form>
        </Drawer>
    );
};

export default CatalogEditDrawer;
