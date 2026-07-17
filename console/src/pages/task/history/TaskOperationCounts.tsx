import React from 'react';
import {Space, Tag} from 'antd';

interface TaskOperationCountsProps {
    priceDownCount?: number;
    listingCount?: number;
    delistCount?: number;
    pendingOperationCount?: number;
}

const TaskOperationCounts: React.FC<TaskOperationCountsProps> = ({
    priceDownCount = 0,
    listingCount = 0,
    delistCount = 0,
    pendingOperationCount = 0,
}) => {
    const counts = [
        {label: '压价', value: priceDownCount, color: 'blue'},
        {label: '上架', value: listingCount, color: 'green'},
        {label: '下架', value: delistCount, color: 'orange'},
        ...(pendingOperationCount > 0
            ? [{label: '上架处理中', value: pendingOperationCount, color: 'gold'}]
            : []),
    ];

    return (
        <Space size={[0, 4]} wrap aria-label="任务成功操作数量">
            {counts.map(item => (
                <Tag key={item.label} color={item.value > 0 ? item.color : undefined}>
                    {item.label} {item.value}
                </Tag>
            ))}
        </Space>
    );
};

export default TaskOperationCounts;
