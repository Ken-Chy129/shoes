import React, {useEffect, useState} from "react";
import {Modal, Table, message} from "antd";
import {doGetRequest} from "@/util/http";
import {TASK_API} from "@/services/task";

interface TaskItemRecord {
    id: number;
    taskId: number;
    title: string;
    listingId: string;
    productId: string;
    styleId: string;
    size: string;
    euSize: string;
    currentPrice: number;
    lowestPrice: number;
    poisonPrice: number;
    poison35Price: number;
    profit35: number;
    profitRate35: number;
    operateResult: string;
    operateTime: string;
}

interface TaskItemModalProps {
    visible: boolean;
    taskId: number | null;
    onClose: () => void;
}

const TaskItemModal: React.FC<TaskItemModalProps> = ({visible, taskId, onClose}) => {
    const [taskItems, setTaskItems] = useState<TaskItemRecord[]>([]);
    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (visible && taskId) {
            queryTaskItems();
        }
    }, [visible, taskId, pageIndex, pageSize]);

    const queryTaskItems = () => {
        if (!taskId) return;
        setLoading(true);
        doGetRequest(TASK_API.TASK_ITEM_PAGE, {taskId, pageIndex, pageSize}, {
            onSuccess: res => {
                setTaskItems(res.data || []);
                setTotal(res.total || 0);
            },
            onFinally: () => setLoading(false)
        });
    }

    const columns = [
        {
            title: '标题',
            dataIndex: 'title',
            key: 'title',
            width: 200,
            ellipsis: true,
        },
        {
            title: '货号',
            dataIndex: 'styleId',
            key: 'styleId',
            width: 120,
        },
        {
            title: '尺码',
            dataIndex: 'size',
            key: 'size',
            width: 80,
        },
        {
            title: 'EU码',
            dataIndex: 'euSize',
            key: 'euSize',
            width: 80,
        },
        {
            title: '当前价',
            dataIndex: 'currentPrice',
            key: 'currentPrice',
            width: 100,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {
            title: '最低价',
            dataIndex: 'lowestPrice',
            key: 'lowestPrice',
            width: 100,
            render: (price: number) => price ? `$${price}` : '-',
        },
        {
            title: '毒价格',
            dataIndex: 'poisonPrice',
            key: 'poisonPrice',
            width: 100,
            render: (price: number) => price ? `¥${price}` : '-',
        },
        {
            title: '毒3.5价格',
            dataIndex: 'poison35Price',
            key: 'poison35Price',
            width: 100,
            render: (price: number) => price ? `¥${price}` : '-',
        },
        {
            title: '3.5利润',
            dataIndex: 'profit35',
            key: 'profit35',
            width: 100,
            render: (profit: number) => {
                if (profit === null || profit === undefined) return '-';
                const color = profit >= 0 ? 'green' : 'red';
                return <span style={{color}}>${profit}</span>;
            },
        },
        {
            title: '3.5利润率',
            dataIndex: 'profitRate35',
            key: 'profitRate35',
            width: 100,
            render: (rate: number) => {
                if (rate === null || rate === undefined) return '-';
                const color = rate >= 0 ? 'green' : 'red';
                return <span style={{color}}>{(rate * 100).toFixed(2)}%</span>;
            },
        },
        {
            title: '操作结果',
            dataIndex: 'operateResult',
            key: 'operateResult',
            width: 120,
        },
        {
            title: '操作时间',
            dataIndex: 'operateTime',
            key: 'operateTime',
            width: 160,
        },
    ];

    const handleClose = () => {
        setPageIndex(1);
        setTaskItems([]);
        setTotal(0);
        onClose();
    }

    return (
        <Modal
            title="任务明细"
            open={visible}
            onCancel={handleClose}
            footer={null}
            width={1400}
        >
            <Table
                columns={columns}
                dataSource={taskItems}
                loading={loading}
                scroll={{x: 1300}}
                pagination={{
                    current: pageIndex,
                    pageSize: pageSize,
                    total: total,
                    showSizeChanger: true,
                    showTotal: (total) => `共 ${total} 条`,
                    onChange: (current, size) => {
                        setPageIndex(current);
                        setPageSize(size);
                    }
                }}
                rowKey="id"
                size="small"
            />
        </Modal>
    );
}

export default TaskItemModal;
