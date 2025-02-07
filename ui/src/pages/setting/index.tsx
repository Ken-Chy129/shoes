import {ProForm, ProFormDigit, ProFormSelect, ProFormText} from '@ant-design/pro-components';
import {Card, Form, message} from 'antd';
import {queryPriceSetting, updatePriceSetting} from "@/services/setting";
import {useEffect, useState} from "react";
import {PriceSetting} from "@/services/types";

const PriceSettingPage = () => {

    const [priceSetting, setPriceSetting] = useState<PriceSetting>();

    useEffect(() => {
        queryPriceSetting().then(
            res => setPriceSetting(res.data)
        );
    }, []);

    const handleSubmit = async (values: any) => {
        try {
            updatePriceSetting(values).then(
                res => {
                    if (res.code == 200) {
                        message.success('配置保存成功！');
                    } else {
                        message.error('配置保存失败！');
                    }
                }
            )
        } catch (error) {
            message.error('配置保存失败！');
        }
    };

    if (!priceSetting) {
        return <div>加载中...</div>; // 或者其他的加载指示器
    }

    return (
        <Card title="价格配置">
            <ProForm onFinish={handleSubmit} initialValues={priceSetting}>
                <ProFormDigit
                    name="exchangeRate"
                    label="汇率"
                    min={0}
                    fieldProps={{precision: 2}}
                    rules={[{required: true}]}
                    initialValue={priceSetting?.exchangeRate}
                />
                <ProFormDigit
                    name="freight"
                    label="运费"
                    min={0}
                    rules={[{required: true}]}
                />
                <ProFormDigit
                    name="platformRate"
                    label="平台抽成费率"
                    min={0}
                    fieldProps={{precision: 2}}
                    rules={[{required: true}]}
                />
                <ProFormDigit
                    name="minProfitRate"
                    label="最小利润率"
                    min={0}
                    fieldProps={{precision: 2}}
                    rules={[{required: true}]}
                />
                <ProFormDigit
                    name="minProfit"
                    label="最小利润"
                    min={0}
                    fieldProps={{precision: 2}}
                    rules={[{required: true}]}
                />
                <ProFormSelect
                    name="priceType"
                    label="价格类型"
                    options={[
                        {label: '普通价格', value: 'normal'},
                        {label: '闪电价格', value: 'flash'},
                        {label: '快速价格', value: 'fast'},
                    ]}
                    rules={[{required: true}]}
                />
                {/*<ProFormSelect*/}
                {/*    name="brand"*/}
                {/*    label="品牌"*/}
                {/*    options={[*/}
                {/*        {label: 'Nike', value: 'nike'},*/}
                {/*        {label: 'Adidas', value: 'adidas'},*/}
                {/*    ]}*/}
                {/*    rules={[{required: true}]}*/}
                {/*/>*/}
                {/*<ProFormText*/}
                {/*    name="modelNumber"*/}
                {/*    label="货号"*/}
                {/*    placeholder="请输入货号"*/}
                {/*/>*/}
                {/*<ProFormDigit*/}
                {/*    name="minProfit"*/}
                {/*    label="最小利润"*/}
                {/*    min={0}*/}
                {/*    fieldProps={{precision: 2}}*/}
                {/*    rules={[{required: true}]}*/}
                {/*/>*/}
                {/*<ProFormDigit*/}
                {/*    name="minProfitRate"*/}
                {/*    label="最小利润率"*/}
                {/*    min={0}*/}
                {/*    max={100}*/}
                {/*    fieldProps={{precision: 2, addonAfter: '%'}}*/}
                {/*    rules={[{required: true}]}*/}
                {/*/>*/}
            </ProForm>
        </Card>
    );
};

export default PriceSettingPage;