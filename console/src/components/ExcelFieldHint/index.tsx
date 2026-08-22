import {InfoCircleOutlined} from '@ant-design/icons';
import {Tag, Typography} from 'antd';
import {createStyles} from 'antd-style';
import React from 'react';

export interface ExcelFieldHintProps {
    requiredFields?: string[];
    optionalFields?: string[];
    requirement?: string;
    note?: string;
}

const formatFields = (fields: string[]) => fields.map(field => `「${field}」`).join('、');

const useStyles = createStyles(({token}) => ({
    root: {
        display: 'flex',
        flexDirection: 'column',
        gap: token.marginXXS,
        paddingTop: token.paddingXXS,
    },
    rule: {
        display: 'grid',
        gridTemplateColumns: 'max-content minmax(0, 1fr)',
        alignItems: 'start',
        gap: token.marginXS,
    },
    requirement: {
        display: 'flex',
        alignItems: 'baseline',
        gap: token.marginXS,
        color: token.colorText,
        lineHeight: token.lineHeight,
    },
    icon: {
        flex: 'none',
        color: token.colorTextSecondary,
    },
    tag: {
        marginInlineEnd: 0,
    },
    value: {
        color: token.colorText,
        lineHeight: token.lineHeight,
    },
    note: {
        color: token.colorTextSecondary,
        fontSize: token.fontSizeSM,
        lineHeight: token.lineHeight,
    },
}));

const ExcelFieldHint: React.FC<ExcelFieldHintProps> = ({
    requiredFields = [], optionalFields = [], requirement, note,
}) => {
    const {styles} = useStyles();

    return (
        <div className={styles.root} role="note" aria-label="Excel字段要求">
            {requirement ? (
                <div className={styles.requirement}>
                    <InfoCircleOutlined className={styles.icon}/>
                    <Typography.Text className={styles.value}>{requirement}</Typography.Text>
                </div>
            ) : requiredFields.length > 0 ? (
                <div className={styles.rule}>
                    <Tag className={styles.tag} color="blue" bordered={false}>必填</Tag>
                    <Typography.Text className={styles.value}>{formatFields(requiredFields)}</Typography.Text>
                </div>
            ) : null}
            {optionalFields.length > 0 && (
                <div className={styles.rule}>
                    <Tag className={styles.tag} bordered={false}>可选</Tag>
                    <Typography.Text type="secondary">{formatFields(optionalFields)}</Typography.Text>
                </div>
            )}
            {note && <Typography.Text className={styles.note}>{note}</Typography.Text>}
        </div>
    );
};

export default ExcelFieldHint;
