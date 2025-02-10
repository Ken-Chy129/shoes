import {ProForm, ProFormDigit, ProFormSelect, ProFormText, ProTable} from '@ant-design/pro-components';
import {Card, Form, message} from 'antd';
import {queryPriceSetting, updatePriceSetting} from "@/services/setting";
import {useEffect, useState} from "react";
import {PriceSetting} from "@/services/types";

const TaskPage = () => {

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
        <Card title="任务列表">
            <ProTable>

            </ProTable>
        </Card>
    );
};

export default TaskPage;