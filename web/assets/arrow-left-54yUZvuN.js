import{a2 as b,a3 as l,b as v}from"./index-CMCQl_IX.js";/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const h=t=>t==="";/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const L=(...t)=>t.filter((e,r,o)=>!!e&&e.trim()!==""&&o.indexOf(e)===r).join(" ").trim();/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const w=t=>t.replace(/([a-z0-9])([A-Z])/g,"$1-$2").toLowerCase();/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const S=t=>t.replace(/^([A-Z])|[\s-_]+(\w)/g,(e,r,o)=>o?o.toUpperCase():r.toLowerCase());/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const W=t=>{const e=S(t);return e.charAt(0).toUpperCase()+e.slice(1)};/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */var s={xmlns:"http://www.w3.org/2000/svg",width:24,height:24,viewBox:"0 0 24 24",fill:"none",stroke:"currentColor","stroke-width":2,"stroke-linecap":"round","stroke-linejoin":"round"};/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const y=Symbol("lucide-icons");function _(){return b(y,{})}/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const $=({name:t,iconNode:e,absoluteStrokeWidth:r,"absolute-stroke-width":o,strokeWidth:c,"stroke-width":k,size:i,color:f,...p},{slots:d})=>{const{size:n,color:C,strokeWidth:m=2,absoluteStrokeWidth:x=!1,class:g=""}=_(),A=v(()=>{const a=h(r)||h(o)||r===!0||o===!0||x===!0,u=c||k||m||s["stroke-width"];return a?Number(u)*24/Number(i??n??s.width):u});return l("svg",{...s,...p,width:i??n??s.width,height:i??n??s.height,stroke:f??C??s.stroke,"stroke-width":A.value,class:L("lucide",g,...t?[`lucide-${w(W(t))}-icon`,`lucide-${w(t)}`]:["lucide-icon"])},[...e.map(a=>l(...a)),...d.default?[d.default()]:[]])};/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const j=(t,e)=>(r,{slots:o,attrs:c})=>l($,{...c,...r,iconNode:e,name:t},o.default?{default:o.default}:void 0);/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const E=[["path",{d:"m12 19-7-7 7-7",key:"1l729n"}],["path",{d:"M19 12H5",key:"x3x0zl"}]],U=j("arrow-left",E);export{U as A,j as c};
