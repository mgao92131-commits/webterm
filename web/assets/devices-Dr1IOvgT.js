import{c as t}from"./createLucideIcon-3A7ZcugA.js";import{l as i}from"./index-DlTmIRWR.js";/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const n=[["rect",{width:"20",height:"14",x:"2",y:"3",rx:"2",key:"48i651"}],["line",{x1:"8",x2:"16",y1:"21",y2:"21",key:"1svkeh"}],["line",{x1:"12",x2:"12",y1:"17",y2:"21",key:"vw1qmm"}]],y=t("monitor",n);/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const o=[["path",{d:"M5 12h14",key:"1ays0h"}],["path",{d:"M12 5v14",key:"s699le"}]],d=t("plus",o);/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const a=[["path",{d:"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",key:"wmoenq"}],["path",{d:"M12 9v4",key:"juzpu7"}],["path",{d:"M12 17h.01",key:"p32p05"}]],h=t("triangle-alert",a);async function p(){return i("/api/devices",{method:"GET"})}async function l(e){return i("/api/devices",{method:"POST",body:JSON.stringify({deviceName:e})})}async function m(e){const s=e.startsWith("d")?e.slice(1):e;return i(`/api/devices/d${s}`,{method:"DELETE"})}export{y as M,d as P,h as T,m as d,p as g,l as r};
