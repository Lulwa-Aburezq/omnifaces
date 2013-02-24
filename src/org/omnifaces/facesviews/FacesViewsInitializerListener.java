/*
 * Copyright 2012 OmniFaces.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.omnifaces.facesviews;

import static org.omnifaces.facesviews.FacesViewsUtils.FACES_VIEWS_ENABLED_PARAM_NAME;
import static org.omnifaces.facesviews.FacesViewsUtils.FACES_VIEWS_RESOURCES_EXTENSIONS;
import static org.omnifaces.facesviews.FacesViewsUtils.getApplicationAttribute;
import static org.omnifaces.facesviews.FacesViewsUtils.getFacesServletRegistration;
import static org.omnifaces.util.Utils.isEmpty;

import java.util.Collection;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;
import javax.servlet.annotation.WebListener;


@WebListener
public class FacesViewsInitializerListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent context) {

        ServletContext servletContext = context.getServletContext();
        
        if (!"false".equals(servletContext.getInitParameter(FACES_VIEWS_ENABLED_PARAM_NAME))) {
        	
        	Set<String> extensions = getApplicationAttribute(servletContext, FACES_VIEWS_RESOURCES_EXTENSIONS);
        	
        	if (!isEmpty(extensions)) {
        		mapFacesServlet(servletContext, extensions);
        	}
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
    	// NOOP.
    }
    
    /**
     * Map the Facelets Servlet to the extension we found
     * 
     * @param extensions collections of extensions encountered during scanning.
     */
    private void mapFacesServlet(ServletContext servletContext, Set<String> extensions) {
    	
        ServletRegistration facesServletRegistration = getFacesServletRegistration(servletContext);
        if (facesServletRegistration != null) {
            Collection<String> mappings = facesServletRegistration.getMappings();
            for (String extension : extensions) {
                if (!mappings.contains(extension)) {
                    facesServletRegistration.addMapping(extension);
                }
            }
        }
    }

}

