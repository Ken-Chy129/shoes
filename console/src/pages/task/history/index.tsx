import {
    Button, DatePicker,
    Form,
    Input,
    message,
    Modal,
    Radio,
    Select,
    Space,
    Table,
    Tabs,
    Tooltip
} from "antd";
import React, {useEffect, useState} from "react";
import {doDeleteRequest, doGetRequest, doPostRequest} from "@/util/http";
import {TEMPLATE_API} from "@/services/management";
import {FieldSelect, MachineSelect, NamespaceSelect} from "@/components";
import {TASK_API} from "@/services/task";
import moment from "moment";
import TaskItemModal from "../components/TaskItemModal";

interface TaskRecord {
    id: number;
    platform: string;
    taskType: string;
    startTime: string;
    endTime: string;
    cost: string;
    status: string;
    round: number;
}

const TaskHistoryPage = () => {
    const [conditionForm] = Form.useForm();

    const [taskList, setTaskList] = useState<TaskRecord[]>([]);

    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    const [taskItemModalVisible, setTaskItemModalVisible] = useState(false);
    const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);

    useEffect(() => {
        queryTaskList();
    }, [pageIndex, pageSize]);

    const queryTaskList = () => {
        let startTime = conditionForm.getFieldValue("startTime");
        if (startTime != null && startTime !== '') {
            startTime = moment(startTime).format('YYYY-MM-DD HH:mm:ss');
        }
        let endTime = conditionForm.getFieldValue("endTime");
        if (endTime != null && endTime !== '') {
            endTime = moment(endTime).format('YYYY-MM-DD HH:mm:ss');
        }
        const taskType = conditionForm.getFieldValue("taskType");
        const platform = conditionForm.getFieldValue("platform");
        const status = conditionForm.getFieldValue("status");
        doGetRequest(TASK_API.PAGE, {taskType, platform, startTime, endTime, status, pageIndex, pageSize}, {
            onSuccess: res => {
                setTaskList(res.data);
                setTotal(res.total);
                message.success("查询成功").then();
            }
        });
    }

    const handleRowClick = (record: TaskRecord) => {
        setSelectedTaskId(record.id);
        setTaskItemModalVisible(true);
    }

    const columns = [
        {
            title: '平台',
            dataIndex: 'platform',
            key: 'platform',
            width: '10%',
        },
        {
            title: '任务类型',
            dataIndex: 'taskType',
            key: 'type',
            width: '12%'
        },
        {
            title: '开始时间',
            dataIndex: 'startTime',
            key: 'startTime',
            width: '14%',
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            key: 'endTime',
            width: '14%',
        },
        {
            title: '耗时',
            dataIndex: 'cost',
            key: 'cost',
            width: '10%',
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: '10%',
            render: (status: string) => {
                const statusMap: Record<string, {text: string, color: string}> = {
                    'running': {text: '运行中', color: 'blue'},
                    'success': {text: '执行成功', color: 'green'},
                    'failed': {text: '执行失败', color: 'red'},
                    'stop': {text: '已暂停', color: 'orange'},
                    'cancel': {text: '已取消', color: 'gray'},
                };
                const statusInfo = statusMap[status] || {text: status, color: 'default'};
                return <span style={{color: statusInfo.color}}>{statusInfo.text}</span>;
            }
        },
        {
            title: '轮次',
            dataIndex: 'round',
            key: 'round',
            width: '8%',
        },
        {
            title: '操作',
            key: 'action',
            width: '10%',
            render: (_: any, record: TaskRecord) => (
                <Button type="link" onClick={() => handleRowClick(record)}>
                    查看明细
                </Button>
            ),
        }
    ];

    return <>
        <Form form={conditionForm}
              style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
            <div style={{display: "flex"}}>
                <Form.Item name="platform" label="平台">
                    <Select
                        style={{width: 160}}
                        placeholder="请选择平台"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: 'KickScrew', value: 'kickscrew'},
                                {label: 'StockX', value: 'stockx'},
                            ]
                        }
                    />
                </Form.Item>
                <Form.Item name="taskType" label="任务类型" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 180}}
                        placeholder="请选择任务类型"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: 'KC改价任务', value: "kc"},
                                {label: 'StockX上架任务', value: "stockx_listing"},
                                {label: 'StockX压价任务', value: "stockx_price_down"},
                            ]
                        }
                    />
                </Form.Item>
                <Form.Item name="status" label="状态" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 160}}
                        placeholder="请选择状态"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '运行中', value: "running"},
                                {label: '执行成功', value: "success"},
                                {label: '执行失败', value: "failed"},
                                {label: '已暂停', value: "stop"},
                                {label: '已取消', value: "cancel"},
                            ]
                        }
                    />
                </Form.Item>
                <Form.Item style={{marginLeft: 30}}>
                    <Button type="primary" htmlType="submit" onClick={queryTaskList}>
                        查询
                    </Button>
                </Form.Item>
                <Form.Item style={{marginLeft: 30}}>
                    <Button type="primary" htmlType="reset" onClick={() => conditionForm.resetFields()}>
                        重置
                    </Button>
                </Form.Item>
            </div>
        </Form>
        <Table
            columns={columns}
            dataSource={taskList}
            pagination={{
                current: pageIndex,
                pageSize: pageSize,
                total: total,
                showSizeChanger: true,
                onChange: (current, pageSize) => {
                    setPageIndex(current);
                    setPageSize(pageSize);
                }
            }}
            rowKey="id"
        />
        <TaskItemModal
            visible={taskItemModalVisible}
            taskId={selectedTaskId}
            onClose={() => {
                setTaskItemModalVisible(false);
                setSelectedTaskId(null);
            }}
        />
    </>
}

export default TaskHistoryPage;
