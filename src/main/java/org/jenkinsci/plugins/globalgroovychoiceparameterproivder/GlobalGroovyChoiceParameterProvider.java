/*
 * The MIT License
 * 
 * Copyright (c) 2013 Michael Rumpf
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.globalgroovychoiceparameterproivder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import groovy.lang.Binding;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.Item;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.XStream2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.scriptsecurity.scripts.ClasspathEntry;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.Mapper;

import jp.ikedam.jenkins.plugins.extensible_choice_parameter.ChoiceListProvider;

/**
 * A choice provider whose choices are determined by a Groovy script.
 */
public class GlobalGroovyChoiceParameterProvider extends ChoiceListProvider
{
    private static final long serialVersionUID = 3L;
    private static final String NoDefaultChoice = "###NODEFAULTCHOICE###";
    private static final Logger LOGGER = Logger.getLogger(GlobalGroovyChoiceParameterProvider.class.getName());
    
    /**
     * The internal class to work with views.
     * 
     * The following files are used (put in main/resource directory in the source tree).
     * <dl>
     *     <dt>config.jelly</dt>
     *         <dd>
     *             Shown as a part of a job configuration page when this provider is selected.
     *             Provides additional configuration fields of a Extensible Choice.
     *         </dd>
     * </dl>
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<ChoiceListProvider>
    {
        /**
         * Create a new instance of {@link GlobalGroovyChoiceParameterProvider} from user inputs.
         * 
         * @param req
         * @param formData
         * @return
         * @throws hudson.model.Descriptor.FormException
         * @see hudson.model.Descriptor#newInstance(org.kohsuke.stapler.StaplerRequest, net.sf.json.JSONObject)
         */
        @Override
        public GlobalGroovyChoiceParameterProvider newInstance(StaplerRequest req, JSONObject formData)
                throws hudson.model.Descriptor.FormException
        {
            GlobalGroovyChoiceParameterProvider provider = (GlobalGroovyChoiceParameterProvider)super.newInstance(req, formData);
            return provider;
        }
        
        /**
         * the display name shown in the dropdown to select a choice provider.
         * 
         * @return display name
         * @see hudson.model.Descriptor#getDisplayName()
         */
        @Override
        public String getDisplayName()
        {
            return "GlobalGroovyChoiceParameterProvider";
        }
        
        /**
         * Returns the selection of a default choice.
         * 
         * @param job
         * @param script
         * @param sandbox
         * @return the selection of a default choice
         */
        public ListBoxModel doFillDefaultChoiceItems(
            @AncestorInPath Job<?, ?> job,
            @RelativePath("groovyScript") @QueryParameter String script,
            @RelativePath("groovyScript") @QueryParameter boolean sandbox
        )
        {
            ListBoxModel ret = new ListBoxModel();
            ret.add("NoDefaultChoice", NoDefaultChoice);
            
            if (job == null)
            {
                // You cannot evaluate scripts without permission checks
                return ret;
            }
            job.checkPermission(Item.CONFIGURE);
            
            if (!sandbox)
            {
                // You cannot evaluate scripts outside sandbox before configuring.
                return ret;
            }
            
            List<String> choices = null;            
            try
            {
                choices = runScript(
                    new SecureGroovyScript(script, sandbox, null).configuringWithNonKeyItem());
            }
            catch(Exception e)
            {
                LOGGER.log(Level.WARNING, "Failed to execute script", e);
            }
            
            if(choices != null)
            {
                for(String choice: choices)
                {
                    ret.add(choice);
                }
            }
            
            return ret;
        }

        /**
         * @return the special value used for "No default choice" (use the top most)
         */
        public String getNoDefaultChoice()
        {
            return NoDefaultChoice;
        }

        public FormValidation doTest(
            @AncestorInPath Job<?, ?> job,
            // Define same as `doFillDefaultChoiceItems`
            // though @RelativePath isn't actually necessary here.
            @RelativePath("groovyScript") @QueryParameter String script
        )
        {
            List<String> choices = null;
            
            if (job == null)
            {
                // You cannot evaluate scripts without permission checks
                return FormValidation.warning("You cannot evaluate scripts outside project configurations");
            }
            job.checkPermission(Item.CONFIGURE);
            
            try
            {
                choices = runScript(
                    new SecureGroovyScript(script, false, Collections.<ClasspathEntry>emptyList()).configuringWithNonKeyItem()                );
            }
            catch(Exception e)
            {
                return FormValidation.error(e, "Failed to execute script");
            }
            
            if(choices == null)
            {
                return FormValidation.error("Script returned null.");
            }
            
            return FormValidation.ok(StringUtils.join(choices, '\n'));
        }
    }
    
    /**
     * Returns the list of choices the user specified in the job configuration page.
     * 
     * @return the list of choices.
     * @see jp.ikedam.jenkins.plugins.extensible_choice_parameter.ChoiceListProvider#getChoiceList()
     */
    @Override
    public List<String> getChoiceList()
    {
        List<String> ret = null;
        String script;
        try
        {
            script = "def gettags = \"ls -1\".execute()\n" +
                    "def tags = []" + "\n" +
                "gettags.text.eachLine {tags.add(it)}" + "\n" +
                "return tags";

            ret = runScript(new SecureGroovyScript(script, false, null).configuringWithNonKeyItem());
        }
        catch(Exception e)
        {
            LOGGER.log(Level.WARNING, "Failed to execute script", e);
        }
        return (ret != null)?ret:new ArrayList<String>(0);
    }

    private static List<String> runScript(SecureGroovyScript groovyScript) throws Exception {
        // see RemotingDiagnostics.Script
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IllegalStateException("Jenkins instance is unavailable.");
        }
        ClassLoader cl = jenkins.getPluginManager().uberClassLoader;

        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
        }

        Binding binding = new Binding();
        Object out = groovyScript.evaluate(cl,  binding);
        if(out == null)
        {
            return null;
        }
        
        if  (!(out instanceof List<?>)) {
            throw new IllegalArgumentException("Return type of the Groovy script must be List<String>");
        }
        
        List<String> ret = new ArrayList<String>();
        for (Object obj : (List<?>) out) {
            if(obj != null) {
                ret.add(obj.toString());
            }
        }
        return ret;
    }

    
    private final String giturl;
    
    
    /**
     * Constructor instantiating with parameters in the configuration page.
     * 
     * @param giturl
     * 
     * @since 1.4.0
     */
    @DataBoundConstructor
    public GlobalGroovyChoiceParameterProvider(String giturl)
    {
        this.giturl = giturl;
        // this.groovyScript = groovyScript.configuringWithNonKeyItem();
        // this.defaultChoice = (!NoDefaultChoice.equals(defaultChoice))?defaultChoice:null;
    }

    // public GlobalGroovyChoiceParameterProvider(String scriptText, String defaultChoice)
    // {
    //     this(
    //         new SecureGroovyScript(scriptText, true, Collections.<ClasspathEntry>emptyList()),
    //         defaultChoice
    //     );
    // }

    public String getGiturl() {
        return "aaaaaa";
    }

}
