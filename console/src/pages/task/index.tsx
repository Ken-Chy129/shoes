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

const TaskPage = () => {
    const [conditionForm] = Form.useForm();

    const [taskList, setTaskList] = useState<Template[]>([]);

    const [pageIndex, setPageIndex] = useState(1);
    const [pageSize, setPageSize] = useState(10);
    const [total, setTotal] = useState(0);

    useEffect(() => {
        queryTaskList();
    }, []);

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
        const operateType = conditionForm.getFieldValue("operateType");
        doGetRequest(TASK_API.PAGE, {taskType, platform, startTime, endTime, status, operateType, pageIndex, pageSize}, {
            onSuccess: res => {
                setTaskList(res.data);
                message.success("查询成功").then();
            }
        });
    }

    const columns = [
        {
            title: '平台',
            dataIndex: 'platform',
            key: 'platform',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '任务类型',
            dataIndex: 'taskType',
            key: 'type',
            width: '10%'
        },
        {
            title: '开始时间',
            dataIndex: 'startTime',
            key: 'startTime',
            width: '12%', // 设置列宽为30%
        },
        {
            title: '结束时间',
            dataIndex: 'endTime',
            key: 'endTime',
            width: '12%', // 设置列宽为30%
        },
        {
            title: '耗时',
            dataIndex: 'cost',
            key: 'cost',
            width: '10%', // 设置列宽为30%
        },
        {
            title: '状态',
            dataIndex: 'status',
            key: 'status',
            width: '8%', // 设置列宽为30%
        },
        {
            title: '操作类型',
            dataIndex: 'operateType',
            key: 'operateType',
            width: '8%', // 设置列宽为30%
        },
        {
            title: '扩展属性',
            dataIndex: 'attributes',
            key: 'attributes',
            width: '30%', // 设置列宽为30%
        },
        // {
        //     title: '操作',
        //     key: 'action',
        //     render: (text: string, templateField: { id: string, fieldId: string, fieldValue: string }) => (
        //         <span>
        //           <Button type="primary" onClick={() => handleFieldPushModal(templateField.id)}>
        //             值推送
        //           </Button>
        //           <Button type="primary" style={{marginLeft: 20}} onClick={() => handleOpenFieldModifiedModal(templateField.id)}>
        //             值修改
        //           </Button>
        //           <Button type="primary" key="delete" style={{marginLeft: 20}} onClick={() => handleOpenFieldDeleteModal(templateField.id)}>
        //             字段删除
        //           </Button>
        //         </span>
        //     ),
        //     width: '22%', // 设置列宽为30%
        // }
    ];

    return <>
        <Form form={conditionForm}
              style={{display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "nowrap"}}>
            <div style={{display: "flex"}}>
                <Form.Item name="platform" label="平台">
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '得物', value: 'poison'},
                                {label: 'KickScrew', value: 'kickscrew'},
                                {label: '绿叉', value: 'stockx'},
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
                    />
                </Form.Item>
                <Form.Item name="taskType" label="任务类型" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '全量刷新商品', value: "refreshAllItems"},
                                {label: '增量刷新商品', value: "refreshIncrementalItems"},
                                {label: '全量刷新价格', value: "refreshAllPrices"},
                                {label: '增量商品价格', value: "refreshIncrementalPrices"},
                                {label: '改价', value: "changePrices"},
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
                    />
                </Form.Item>
                <Form.Item name="startTime" label="开始时间" style={{marginLeft: 20}}>
                    <DatePicker
                        showTime={{ format: 'HH:mm:ss' }}
                        format="YYYY-MM-DD HH:mm:ss"
                    />
                </Form.Item>
                <Form.Item name="endTime" label="结束时间" style={{marginLeft: 20}}>
                    <DatePicker
                        showTime={{ format: 'HH:mm' }}
                        format="YYYY-MM-DD HH:mm"
                    />
                </Form.Item>
                <Form.Item name="status" label="状态" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '运行中', value: "running"},
                                {label: '执行完成', value: "success"},
                                {label: '执行失败', value: "failed"},
                                {label: '执行中止', value: "terminated"},
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
                    />
                </Form.Item>
                <Form.Item name="operateType" label="操作类型" style={{marginLeft: 20}}>
                    <Select
                        style={{width: 160}}
                        placeholder="请选择字段"
                        allowClear
                        showSearch={true}
                        optionFilterProp="label"
                        options={
                            [
                                {label: '人工执行', value: "manually"},
                                {label: '系统触发', value: "system"}
                            ]
                        }
                        notFoundContent={"该命名空间下暂无字段"}
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
    </>
}

export default TaskPage;