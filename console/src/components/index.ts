/**
 * 这个文件作为组件的目录
 * 目的是统一管理对外输出的组件，方便分类
 */
/**
 * 布局组件
 */
import {Loading} from "./Common/Loading";
import {NamespaceSelect, FieldSelect, MachineSelect} from "@/components/CustomSelect";
import InfoCard from './Card';
import Footer from './Footer';
import { Question, SelectLang } from './RightContent';
import { AvatarDropdown, AvatarName } from './RightContent/AvatarDropdown';

export { Loading, Footer, Question, SelectLang, AvatarDropdown, AvatarName, InfoCard, NamespaceSelect, FieldSelect, MachineSelect };
