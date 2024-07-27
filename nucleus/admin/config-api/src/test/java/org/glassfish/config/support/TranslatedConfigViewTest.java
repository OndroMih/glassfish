/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.config.support;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import jakarta.inject.Inject;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.tests.utils.junit.HK2JUnit5Extension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ExtendWith(HK2JUnit5Extension.class)
public class TranslatedConfigViewTest {

    @Inject
    ServiceLocator locator;

    @BeforeEach
    void setUpTest() {
        TranslatedConfigView.setHabitat(locator);
        System.setProperty("myProperty", "myValue");
        System.setProperty("myProperty2", "someOtherValue");
    }

    @ParameterizedTest(name = "[index] propertySubstitution: {0}")
    @CsvSource(useHeadersInDisplayName = true,
            value = {
            "description                        , expression                        , expectedValue",
            "property                           , ${myProperty}                     , myValue",
            "no property                        , text                              , text",
            "missing property                     , ${missingProperty}              , ${missingProperty}",
            "property with text before and after, before-${myProperty}-after        , before-myValue-after",
            "two properties                     , ${myProperty}.a.${myProperty2}    , myValue.a.someOtherValue",
            "property with default value        , ${missingProperty:default}        , default",
            "property default value includes :  , ${missingProperty:default:value}  , default:value",
            })
    void propertySubstitution(String description, String expression, String expectedValue) {
        String expandedValue = TranslatedConfigView.expandValue(expression);
        assertThat("expandedValue", expandedValue, is(equalTo(expectedValue)));
    }

}
