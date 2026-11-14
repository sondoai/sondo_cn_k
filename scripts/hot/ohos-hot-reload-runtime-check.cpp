
// 使用真实Runtime构造/析构代码，模拟JSVM在不同分配阶段报错，验证资源所有权。
// 该测试不替代鸿蒙真机上的JS引擎与UI验证。
#include "../ohosApp/entry/src/main/cpp/hotreload/SondoHotReload.cpp"
#include <cassert>
#include <iostream>
static int stage=0, vms=0, envs=0, scopes=0;
JSVM_Status OH_JSVM_Init(const JSVM_InitOptions*) { return JSVM_OK; }
JSVM_Status OH_JSVM_CreateVM(const JSVM_CreateVMOptions*, JSVM_VM* p) { *p=reinterpret_cast<JSVM_VM>(new int(1));vms++;return JSVM_OK; }
JSVM_Status OH_JSVM_DestroyVM(JSVM_VM p) { delete reinterpret_cast<int*>(p);vms--;return JSVM_OK; }
JSVM_Status OH_JSVM_OpenVMScope(JSVM_VM,JSVM_VMScope*p) { if(stage==0)return JSVM_GENERIC_FAILURE;*p=reinterpret_cast<JSVM_VMScope>(1);scopes++;return JSVM_OK; }
JSVM_Status OH_JSVM_CloseVMScope(JSVM_VM,JSVM_VMScope) {scopes--;return JSVM_OK;}
JSVM_Status OH_JSVM_CreateEnv(JSVM_VM,size_t,const JSVM_PropertyDescriptor*,JSVM_Env*p) {if(stage==1)return JSVM_GENERIC_FAILURE;*p=reinterpret_cast<JSVM_Env>(new int(2));envs++;return JSVM_OK;}
JSVM_Status OH_JSVM_DestroyEnv(JSVM_Env p) {delete reinterpret_cast<int*>(p);envs--;return JSVM_OK;}
JSVM_Status OH_JSVM_IsLocked(JSVM_Env,bool*p) {*p=true;return JSVM_OK;}
JSVM_Status OH_JSVM_AcquireLock(JSVM_Env) {return JSVM_OK;}
JSVM_Status OH_JSVM_ReleaseLock(JSVM_Env) {return JSVM_OK;}
JSVM_Status OH_JSVM_OpenEnvScope(JSVM_Env,JSVM_EnvScope*p) {*p=reinterpret_cast<JSVM_EnvScope>(1);scopes++;return JSVM_OK;}
JSVM_Status OH_JSVM_CloseEnvScope(JSVM_Env,JSVM_EnvScope) {scopes--;return JSVM_OK;}
JSVM_Status OH_JSVM_OpenHandleScope(JSVM_Env,JSVM_HandleScope*p) {*p=reinterpret_cast<JSVM_HandleScope>(1);scopes++;return JSVM_OK;}
JSVM_Status OH_JSVM_CloseHandleScope(JSVM_Env,JSVM_HandleScope) {scopes--;return JSVM_OK;}
JSVM_Status OH_JSVM_CreateStringUtf8(JSVM_Env,const char*,size_t,JSVM_Value*p) {*p=reinterpret_cast<JSVM_Value>(1);return JSVM_OK;}
JSVM_Status OH_JSVM_CompileScript(JSVM_Env,JSVM_Value,const uint8_t*,size_t,bool,bool*,JSVM_Script*p) {if(stage==2)return JSVM_PENDING_EXCEPTION;*p=reinterpret_cast<JSVM_Script>(1);return JSVM_OK;}
JSVM_Status OH_JSVM_RunScript(JSVM_Env,JSVM_Script,JSVM_Value*) {return JSVM_PENDING_EXCEPTION;}
JSVM_Status OH_JSVM_IsExceptionPending(JSVM_Env,bool*p) {*p=false;return JSVM_OK;}
int OH_LOG_Print(LogType,LogLevel,unsigned int,const char*,const char*,...) {return 0;}
int main() {
 for(stage=0;stage<4;stage++) {
  for(int attempt=0;attempt<100;attempt++) {
   bool failed=false;
   try { Runtime runtime("bad-bundle", "throw new Error('bad syntax')"); }
   catch (const std::exception&) { failed=true; }
   assert(failed);
   assert(vms==0 && envs==0 && scopes==0);
  }
 }
 std::cout << "PASS: 400 constructor/compile/evaluation failures release every VM, env and scope\n";
}
