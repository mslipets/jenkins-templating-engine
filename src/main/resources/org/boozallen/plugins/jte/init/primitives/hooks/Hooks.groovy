/*
   Copyright 2018 Booz Allen Hamilton

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package org.boozallen.plugins.jte.init.primitives.hooks

import com.cloudbees.groovy.cps.NonCPS
import java.lang.annotation.Annotation
import jenkins.model.Jenkins
import org.boozallen.plugins.jte.init.primitives.injectors.LibraryLoader
import org.boozallen.plugins.jte.util.RunUtils
import org.boozallen.plugins.jte.util.TemplateLogger
import org.boozallen.plugins.jte.util.TemplateScriptEngine
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.jenkinsci.plugins.workflow.cps.CpsThread

class Hooks implements Serializable{

    @NonCPS
    static List<AnnotatedMethod> discover(Class<? extends Annotation> hookType, Binding binding){
        List<AnnotatedMethod> discovered = new ArrayList() 

        TemplateLogger logger = TemplateLogger.createDuringRun() 
        Class StepWrapper = LibraryLoader.getPrimitiveClass()
        ArrayList stepWrappers = binding.getVariables().collect{ it.value }.findAll{
            StepWrapper.getName().equals(it.getClass().getName())
        }

        stepWrappers.each{ step ->
            step.impl.class.methods.each{ method ->
                def annotation = method.getAnnotation(hookType)
                if (annotation){
                    AnnotatedMethod am = new AnnotatedMethod(annotation, hookType.getSimpleName(), method.name, step)
                    discovered.push(am) 
                }            
            }
        }

        return discovered
    }

    static void invoke(Class<? extends Annotation> annotation, Binding binding, HookContext context = new HookContext()){
        discover(annotation, binding).each{ hook -> 
            if(shouldInvoke(hook, context)){
                hook.invoke(context)
            }
         }
    }

    static def shouldInvoke(AnnotatedMethod hook, HookContext context){
        def annotation = hook.getAnnotation()
        def stepWrapper = hook.getStepWrapper() 
        String configVar = stepWrapper.getClass().libraryConfigVariable
        Map config = stepWrapper.impl."get${configVar.capitalize()}"()
        RunWrapper currentBuild = getRunWrapper()
        Binding invokeBinding = new Binding([context: context, config: config, currentBuild: currentBuild])
        def result
        try{
            result = annotation.value().newInstance(invokeBinding, invokeBinding).call()
        }catch(any){
            TemplateLogger.printWarning "Exception thrown while evaluating @${hook.annotationName} on ${hook.methodName} in ${hook.stepWrapper.getName()} from ${hook.stepWrapper.getLibrary()} library."
            throw any 
        }
        return result
    }

    static RunWrapper getRunWrapper(){
        CpsThread thread = CpsThread.current()
        if(!thread){
            throw new Exception("CpsThread not present.")
        }
        WorkflowRun run = thread.getExecution().getOwner().run() 
        return new RunWrapper(run, true)
    }
    
}