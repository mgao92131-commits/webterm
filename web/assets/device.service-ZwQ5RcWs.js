import{c as t}from"./arrow-left-54yUZvuN.js";import{R as n}from"./index-CMCQl_IX.js";/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const o=[["rect",{width:"20",height:"14",x:"2",y:"3",rx:"2",key:"48i651"}],["line",{x1:"8",x2:"16",y1:"21",y2:"21",key:"1svkeh"}],["line",{x1:"12",x2:"12",y1:"17",y2:"21",key:"vw1qmm"}]],v=t("monitor",o);/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const s=[["path",{d:"M5 12h14",key:"1ays0h"}],["path",{d:"M12 5v14",key:"s699le"}]],p=t("plus",s);/**
 * @license @lucide/vue v1.21.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const a=[["path",{d:"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",key:"wmoenq"}],["path",{d:"M12 9v4",key:"juzpu7"}],["path",{d:"M12 17h.01",key:"p32p05"}]],f=t("triangle-alert",a);async function i(){return n("/api/devices",{method:"GET"})}async function r(e){return n("/api/devices",{method:"POST",body:JSON.stringify({deviceName:e})})}async function d(e){const c=e.startsWith("d")?e.slice(1):e;return n(`/api/devices/d${c}`,{method:"DELETE"})}function y(e){return{deviceId:e.deviceId,deviceName:e.deviceName,status:e.online?"online":"offline"}}async function l(){return i()}async function m(){return(await i()).map(y)}async function k(e){return r(e)}async function D(e){return d(e)}export{v as M,p as P,f as T,l as a,k as c,m as f,D as r};
