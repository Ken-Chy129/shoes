import {InfoCircleOutlined} from '@ant-design/icons';
import {Space, Tag, Typography} from 'antd';
import React from 'react';

export interface ExcelFieldHintProps {
    requiredFields?: string[];
    optionalFields?: string[];
    requirement?: string;
    note?: string;
}

const formatFields = (fields: string[]) => fields.map(field => `「${field}」`).join('、');

const ExcelFieldHint: React.FC<ExcelFieldHintProps> = ({
    requiredFields = [], optionalFields = [], requirement, note,
}) => (
    <Space direction="vertical" size={2} role="note" aria-label="Excel字段要求">
        <Space size={4} wrap>
            <Typography.Text type="secondary"><InfoCircleOutlined/> Excel字段要求</Typography.Text>
            {requirement ? (
                <><Tag color="blue">填写规则</Tag><Typography.Text>{requirement}</Typography.Text></>
            ) : requiredFields.length > 0 ? (
                <><Tag color="blue">必填</Tag><Typography.Text>{formatFields(requiredFields)}</Typography.Text></>
            ) : null}
        </Space>
        {optionalFields.length > 0 && (
            <Space size={4} wrap>
                <Tag>可选</Tag>
                <Typography.Text type="secondary">{formatFields(optionalFields)}</Typography.Text>
            </Space>
        )}
        {note && <Typography.Text type="secondary">{note}</Typography.Text>}
    </Space>
);

export default ExcelFieldHint;
