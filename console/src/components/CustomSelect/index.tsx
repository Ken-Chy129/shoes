import {Form, FormInstance, Select} from "antd";
import React, {useEffect, useState} from "react";
import {doGetRequest} from "@/util/http";
import {FIELD_API, NAMESPACE_API} from "@/services/management";
import {MACHINE_API} from "@/services/app";

export const NamespaceSelect: React.FC<{
    form: FormInstance
    style?: React.CSSProperties
    onChange?: (value: any) => void
}> = ({form, style = {minWidth: 250}, onChange}) => {
    const [namespaceList, setNamespaceList] = useState<Namespace[]>([]);

    useEffect(() => {
        queryNamespace();
    }, []);

    const queryNamespace = () => {
        doGetRequest(NAMESPACE_API.LIST_BY_APPID, {}, {
            onSuccess: (res: any) => {
                res.data.forEach((namespace: any) => {
                    namespace.label = namespace.name;
                    namespace.value = namespace.id
                });
                setNamespaceList(res.data);
            }
        });
    }

    return (
        <Select
            placeholder="请选择命名空间"
            allowClear
            showSearch={true}
            optionFilterProp="label"
            style={style}
            options={namespaceList}
            value={form.getFieldValue("namespaceId")}
            onChange={onChange}
            notFoundContent={"暂无命名空间"}
        />
    )
}

export const FieldSelect: React.FC<{
    form: FormInstance,
    style?: React.CSSProperties
    onChange?: (value: any) => void
}> = ({form, style = {minWidth: 250}, onChange}) => {
    const [fieldList, setFieldList] = useState<Field[]>([]);
    const namespaceId = Form.useWatch("namespaceId", form);

    useEffect(() => {
        if (namespaceId) {
            form.setFieldValue("field", null);
            queryNamespaceField();
        }
    }, [namespaceId]);

    const queryNamespaceField = () => {
        doGetRequest(FIELD_API.LIST_BY_NAMESPACE_ID, {namespaceId}, {
            onSuccess: (res: any) => {
                res.data.forEach((field: any) => {
                    field.label = field.name;
                    field.value = field.id;
                });
                setFieldList(res.data);
            }
        });
    }

    return (
        <Select
            placeholder="请选择字段"
            allowClear
            showSearch={true}
            optionFilterProp="label"
            style={style}
            options={fieldList}
            value={form.getFieldValue("field")}
            onChange={onChange}
            notFoundContent={"该命名空间下暂无字段"}
        />
    )
}

export const MachineSelect: React.FC<{
    form: FormInstance,
    style?: React.CSSProperties
    mode?: 'multiple' | 'tags',
    onChange?: (value: any) => void
}
> = ({form, style = {minWidth: 250}, mode, onChange}) => {
    const [machineList, setMachineList] = useState<[]>([]);

    useEffect(() => {
        queryMachineList();
    }, []);

    const queryMachineList = () => {
        doGetRequest(MACHINE_API.LIST, {}, {
            onSuccess: (res: any) => {
                res.data.forEach((machine: any) => {
                    machine.label = machine.ipAddress + ":" + machine.port;
                    machine.value = machine.ipAddress + ":" + machine.port;
                });
                setMachineList(res.data);
            }
        });
    }

    return (
        <Select
            placeholder="请选择目标机器"
            allowClear
            showSearch={true}
            optionFilterProp="label"
            mode={mode}
            style={style}
            options={machineList}
            value={form.getFieldValue("machines")}
            onChange={onChange}
            notFoundContent={"暂无机器"}
        />
    )
}